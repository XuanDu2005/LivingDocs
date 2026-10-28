package com.livingdocs.modules.ai.settings;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.TooManyRequestsException;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;
import com.livingdocs.modules.admin.repository.PlatformDocTypePolicyRepository;
import com.livingdocs.modules.admin.repository.PlatformSettingsRepository;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The single place where the platform's governance rules are evaluated.
 *
 * <p>Before Phase 7 the knobs existed ({@code requireManagerApproval},
 * {@code aiConfidenceThreshold}, {@code mergePolicyCritical},
 * {@code platform_doc_type_policies}, token budgets) but nothing in the
 * runtime path consulted them — every generated version went into the
 * review queue regardless, and the merge policy was a decorative
 * checkbox. This service makes them real.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Doc-type workflow</b> — a platform policy per
 *       {@code doc_type} decides whether generated content is published
 *       automatically ({@code AUTO_APPLY}) or queued for review
 *       ({@code MANAGER_REVIEW}).</li>
 *   <li><b>Manager approval</b> — when the workspace requires it, a
 *       generated version stops at the Staff tier; it cannot be
 *       published without a Manager decision.</li>
 *   <li><b>Confidence threshold</b> — a generation whose confidence
 *       falls below the workspace threshold is never auto-applied. It
 *       is always routed to a human.</li>
 *   <li><b>Token budget</b> — generation is refused when the
 *       workspace has exhausted its daily or monthly token allowance.</li>
 *   <li><b>Merge policy</b> — the {@code BLOCK} policy turns critical
 *       open drift alerts into a hard merge gate.</li>
 * </ul>
 *
 * <p>Every method is a pure read of the current configuration, so the
 * decision is easy to explain in the audit log.
 */
@Service
public class GovernancePolicyService {

    private static final Logger log = LoggerFactory.getLogger(GovernancePolicyService.class);

    /** The workflow applied when no doc-type policy matches. */
    public static final PlatformDocTypePolicy.Workflow DEFAULT_WORKFLOW =
            PlatformDocTypePolicy.Workflow.MANAGER_REVIEW;

    private final PlatformDocTypePolicyRepository docTypePolicyRepository;
    private final PlatformSettingsRepository platformSettingsRepository;
    private final WorkspaceSettingsRepository workspaceSettingsRepository;
    private final DriftAlertRepository driftAlertRepository;
    private final AiRateLimitService rateLimitService;
    private final ConfidenceThresholdService confidenceThresholdService;

    public GovernancePolicyService(PlatformDocTypePolicyRepository docTypePolicyRepository,
                                   PlatformSettingsRepository platformSettingsRepository,
                                   WorkspaceSettingsRepository workspaceSettingsRepository,
                                   DriftAlertRepository driftAlertRepository,
                                   AiRateLimitService rateLimitService,
                                   ConfidenceThresholdService confidenceThresholdService) {
        this.docTypePolicyRepository = docTypePolicyRepository;
        this.platformSettingsRepository = platformSettingsRepository;
        this.workspaceSettingsRepository = workspaceSettingsRepository;
        this.driftAlertRepository = driftAlertRepository;
        this.rateLimitService = rateLimitService;
        this.confidenceThresholdService = confidenceThresholdService;
    }

    // -----------------------------------------------------------------
    // doc-type workflow
    // -----------------------------------------------------------------

    /**
     * Resolve the workflow that applies to a documentation type.
     *
     * <p>Looks up the platform's {@code platform_doc_type_policies} row for
     * the type. When the workspace has no explicit policy of its own the
     * platform default applies; when neither exists the safe default is
     * {@link PlatformDocTypePolicy.Workflow#MANAGER_REVIEW}.
     */
    @Transactional(readOnly = true)
    public PlatformDocTypePolicy.Workflow workflowFor(UUID workspaceId, String docType) {
        var explicit = explicitWorkflow(docType);
        if (explicit != null) {
            return explicit;
        }
        // Fall back to the platform-wide "require manager approval" default.
        var platform = platformSettingsRepository.getSingleton();
        return platform.isDefaultRequireManagerApproval()
                ? PlatformDocTypePolicy.Workflow.MANAGER_REVIEW
                : PlatformDocTypePolicy.Workflow.AUTO_APPLY;
    }

    /**
     * The explicit policy for a doc type, or {@code null} when the
     * platform has no row for it.
     */
    @Transactional(readOnly = true)
    public PlatformDocTypePolicy.Workflow explicitWorkflow(String docType) {
        if (docType == null || docType.isBlank()) {
            return null;
        }
        return docTypePolicyRepository.findByDocType(normalizeDocType(docType))
                .map(PlatformDocTypePolicy::getWorkflow)
                .orElse(null);
    }

    /**
     * Whether a generated version of this doc type may be published
     * without a human decision.
     *
     * <p>Precedence: an explicit per-doc-type policy is the most specific
     * rule and therefore wins. The workspace's
     * {@code requireManagerApproval} flag is the fallback used only for
     * doc types the platform has no policy for.
     */
    @Transactional(readOnly = true)
    public boolean isAutoApply(UUID workspaceId, String docType) {
        var explicit = explicitWorkflow(docType);
        if (explicit != null) {
            return explicit == PlatformDocTypePolicy.Workflow.AUTO_APPLY;
        }
        return !requiresManagerApproval(workspaceId);
    }

    /**
     * Whether the workspace demands a Manager decision before content
     * becomes canonical.
     */
    @Transactional(readOnly = true)
    public boolean requiresManagerApproval(UUID workspaceId) {
        WorkspaceSettings settings = workspaceSettingsRepository.findById(workspaceId).orElse(null);
        if (settings != null) {
            return settings.isRequireManagerApproval();
        }
        return platformSettingsRepository.getSingleton().isDefaultRequireManagerApproval();
    }

    // -----------------------------------------------------------------
    // confidence threshold
    // -----------------------------------------------------------------

    /**
     * Whether a generation result is trustworthy enough to be applied
     * without a human reading it.
     *
     * <p>A result with no confidence score is treated as
     * <em>not</em> confident — the AI service always reports one, so a
     * missing value means the call did not complete properly.
     */
    @Transactional(readOnly = true)
    public boolean meetsConfidenceThreshold(UUID workspaceId, Float confidence) {
        if (confidence == null) {
            return false;
        }
        float threshold = confidenceThresholdService.resolve(workspaceId);
        return confidence >= threshold;
    }

    /**
     * The effective threshold for the workspace, in {@code [0, 1]}.
     */
    @Transactional(readOnly = true)
    public float confidenceThreshold(UUID workspaceId) {
        return confidenceThresholdService.resolve(workspaceId);
    }

    // -----------------------------------------------------------------
    // token budget
    // -----------------------------------------------------------------

    /**
     * Assert the workspace still has budget for another AI run.
     *
     * @throws TooManyRequestsException when the daily or monthly token
     *         allowance has been exhausted
     */
    @Transactional(readOnly = true)
    public void requireBudget(UUID workspaceId) {
        if (!rateLimitService.checkBudget(workspaceId)) {
            var summary = rateLimitService.getSummary(workspaceId);
            throw new TooManyRequestsException(
                    "AI token budget exhausted for this workspace "
                            + "(today: " + summary.todayTokens() + " tokens, month: "
                            + summary.monthTokens() + " tokens). "
                            + "Raise the limit in workspace settings or wait for the next period.");
        }
    }

    // -----------------------------------------------------------------
    // merge policy
    // -----------------------------------------------------------------

    /**
     * The merge policy that applies to the workspace: {@code WARN} or
     * {@code BLOCK}.
     */
    @Transactional(readOnly = true)
    public String mergePolicy(UUID workspaceId) {
        WorkspaceSettings settings = workspaceSettingsRepository.findById(workspaceId).orElse(null);
        if (settings != null) {
            return normalizePolicy(settings.getMergePolicyCritical());
        }
        return normalizePolicy(platformSettingsRepository.getSingleton().getDefaultMergePolicyCritical());
    }

    /**
     * Whether critical open drift should block a merge.
     */
    @Transactional(readOnly = true)
    public boolean blocksOnCriticalDrift(UUID workspaceId) {
        return "BLOCK".equals(mergePolicy(workspaceId));
    }

    /**
     * The severity at or above which drift is considered significant.
     */
    @Transactional(readOnly = true)
    public DriftSeverity driftSeverityThreshold(UUID workspaceId) {
        WorkspaceSettings settings = workspaceSettingsRepository.findById(workspaceId).orElse(null);
        String raw = settings != null
                ? settings.getDriftSeverityThreshold()
                : platformSettingsRepository.getSingleton().getDefaultDriftSeverityThreshold();
        try {
            return DriftSeverity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DriftSeverity.MEDIUM;
        }
    }

    /**
     * Evaluate the merge gate for a pull request.
     *
     * <p>Returns a verdict the CI/CD integration can act on:
     * <ul>
     *   <li>{@code blocked=true} when the workspace policy is
     *       {@code BLOCK} <em>and</em> at least one open drift alert is
     *       at or above the severity threshold.</li>
     *   <li>{@code blocked=false} otherwise, with the reason
     *       explaining which alerts were considered.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public MergeVerdict evaluateMergeGate(UUID workspaceId, UUID pullRequestId) {
        String policy = mergePolicy(workspaceId);
        DriftSeverity threshold = driftSeverityThreshold(workspaceId);

        List<DriftAlert> alerts = pullRequestId != null
                ? driftAlertRepository.findAllByPullRequestIdOrderByDetectedAtDesc(pullRequestId)
                : driftAlertRepository.findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
                        workspaceId, DriftResolution.OPEN);

        List<DriftAlert> open = alerts.stream()
                .filter(a -> a.getResolutionStatus() == DriftResolution.OPEN)
                .filter(a -> a.getSeverity().ordinal() >= threshold.ordinal())
                .toList();

        boolean blocking = "BLOCK".equals(policy) && !open.isEmpty();
        String reason = open.isEmpty()
                ? "No open drift alerts at or above severity " + threshold
                : open.size() + " open drift alert(s) at or above severity " + threshold
                    + (blocking ? " — merge blocked by policy BLOCK" : " — merge allowed (policy WARN)");

        return new MergeVerdict(
                blocking,
                policy,
                threshold,
                open.size(),
                open.stream().map(DriftAlert::getId).toList(),
                reason);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static String normalizeDocType(String docType) {
        return docType.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePolicy(String raw) {
        if (raw == null || raw.isBlank()) return "WARN";
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The outcome of a merge-gate evaluation.
     */
    public record MergeVerdict(
            boolean blocked,
            String policy,
            DriftSeverity severityThreshold,
            int blockingAlertCount,
            List<UUID> blockingAlertIds,
            String reason) {
    }
}
