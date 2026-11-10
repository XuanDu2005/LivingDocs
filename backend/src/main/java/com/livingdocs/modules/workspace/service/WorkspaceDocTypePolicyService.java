package com.livingdocs.modules.workspace.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy.Workflow;
import com.livingdocs.modules.admin.repository.PlatformDocTypePolicyRepository;
import com.livingdocs.modules.admin.repository.PlatformSettingsRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.template.model.DocType;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceDocTypePolicyRequest;
import com.livingdocs.modules.workspace.dto.WorkspaceDocTypePolicyResponse;
import com.livingdocs.modules.workspace.model.WorkspaceDocTypePolicy;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceDocTypePolicyRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the per-workspace overrides of the doc-type workflow policy.
 *
 * <p>Before this service the doc-type workflow could only be configured
 * at the platform level, through
 * {@code /admin/platform-settings/doc-type-policies}. A workspace
 * manager who wanted their {@code API_REFERENCE} documents to require a
 * manager approval had no way to say so — the only lever was the
 * workspace-wide {@code requireManagerApproval} flag, which applies to
 * every doc type at once.
 *
 * <p>The overrides are deliberately sparse: a row exists only for the
 * doc types the workspace actually changed. Everything else keeps
 * inheriting the platform policy, so adding an override for one doc type
 * cannot silently change the behaviour of the others.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>the workspace's own row for the doc type,</li>
 *   <li>the platform's row for the doc type,</li>
 *   <li>the workspace's {@code requireManagerApproval} flag,</li>
 *   <li>the platform's {@code default_require_manager_approval} flag.</li>
 * </ol>
 *
 * <p>{@code GovernancePolicyService} evaluates the same chain at
 * runtime, so what this screen shows and what the runtime enforces
 * cannot drift apart.
 */
@Service
public class WorkspaceDocTypePolicyService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkspaceDocTypePolicyService.class);

    private final WorkspaceDocTypePolicyRepository repository;
    private final PlatformDocTypePolicyRepository platformDocTypePolicyRepository;
    private final WorkspaceSettingsRepository workspaceSettingsRepository;
    private final PlatformSettingsRepository platformSettingsRepository;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;

    public WorkspaceDocTypePolicyService(
            WorkspaceDocTypePolicyRepository repository,
            PlatformDocTypePolicyRepository platformDocTypePolicyRepository,
            WorkspaceSettingsRepository workspaceSettingsRepository,
            PlatformSettingsRepository platformSettingsRepository,
            WorkspaceService workspaceService,
            AuditLogService auditLogService) {
        this.repository = repository;
        this.platformDocTypePolicyRepository = platformDocTypePolicyRepository;
        this.workspaceSettingsRepository = workspaceSettingsRepository;
        this.platformSettingsRepository = platformSettingsRepository;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
    }

    /**
     * The resolved policy of every canonical doc type in this workspace.
     *
     * <p>All canonical types are listed, not only the ones with a row:
     * a manager has to be able to see that {@code README} is currently
     * auto-applied <em>because it is inherited</em>, otherwise they
     * cannot tell the difference between "I set this" and "this is the
     * platform's rule".
     *
     * <p>Requires workspace membership.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceDocTypePolicyResponse> list(UUID workspaceId, UUID actorId) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        List<WorkspaceDocTypePolicy> rows =
                repository.findAllByWorkspaceIdOrderByDocTypeAsc(workspaceId);
        Map<String, WorkspaceDocTypePolicy> byDocType = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkspaceDocTypePolicy::getDocType,
                        r -> r,
                        (a, b) -> a));

        List<WorkspaceDocTypePolicyResponse> out = new ArrayList<>();
        for (DocType type : DocType.values()) {
            String docType = type.name();
            PlatformDocTypePolicy platformRow =
                    platformDocTypePolicyRepository.findByDocType(docType).orElse(null);
            out.add(WorkspaceDocTypePolicyResponse.of(
                    docType,
                    byDocType.get(docType),
                    platformRow,
                    fallbackWorkflow(workspaceId)));
        }
        return out;
    }

    /**
     * Set the workflow this workspace applies to one doc type.
     *
     * <p>Requires the workspace {@code MANAGER} or
     * {@code DOCUMENTATION_OWNER} role, on top of the
     * {@code GOVERNANCE_MANAGE} capability the endpoint enforces. The
     * change is written to the audit trail, because it decides whether
     * generated content reaches the readers without a human ever reading
     * it.
     */
    @Transactional
    public WorkspaceDocTypePolicyResponse upsert(UUID workspaceId, UUID actorId,
                                                  String docType,
                                                  UpdateWorkspaceDocTypePolicyRequest req) {
        workspaceService.requireDocumentationGovernance(actorId, workspaceId);

        String normalized = normalizeDocType(docType);
        Workflow workflow = parseWorkflow(req.workflow());

        WorkspaceDocTypePolicy row = repository
                .findByWorkspaceIdAndDocType(workspaceId, normalized)
                .orElse(null);

        String previousEffective = effectiveWorkflow(workspaceId, normalized).name();
        String previousOverride = row == null ? null : row.getWorkflow().name();

        if (row == null) {
            row = new WorkspaceDocTypePolicy(workspaceId, normalized, workflow);
        } else {
            row.changeWorkflow(workflow);
        }
        WorkspaceDocTypePolicy saved = repository.save(row);

        auditLogService.record(actorId, "MANAGER",
                "workspace.doc_type_policy.update",
                "workspace_doc_type_policy",
                saved.getId().toString(),
                workspaceId,
                Map.of("docType", normalized,
                        "previousOverride", previousOverride == null ? "INHERITED" : previousOverride,
                        "previousEffective", previousEffective,
                        "workflow", workflow.name()));

        log.info("Workspace {} doc-type policy {} = {} (actor={}, effective {} -> {})",
                workspaceId, normalized, workflow, actorId,
                previousEffective, workflow.name());

        return describe(workspaceId, normalized, saved);
    }

    /**
     * Drop the workspace's override for a doc type, so it inherits the
     * platform policy again.
     *
     * <p>Requires the workspace {@code MANAGER} or
     * {@code DOCUMENTATION_OWNER} role.
     */
    @Transactional
    public void delete(UUID workspaceId, UUID actorId, String docType) {
        workspaceService.requireDocumentationGovernance(actorId, workspaceId);

        String normalized = normalizeDocType(docType);
        WorkspaceDocTypePolicy row = repository
                .findByWorkspaceIdAndDocType(workspaceId, normalized)
                .orElseThrow(() -> new NotFoundException(
                        "This workspace has no doc-type policy for " + normalized));

        String previousEffective = effectiveWorkflow(workspaceId, normalized).name();
        repository.delete(row);

        auditLogService.record(actorId, "MANAGER",
                "workspace.doc_type_policy.delete",
                "workspace_doc_type_policy",
                row.getId().toString(),
                workspaceId,
                Map.of("docType", normalized,
                        "previousOverride", row.getWorkflow().name(),
                        "previousEffective", previousEffective));

        log.info("Workspace {} doc-type policy {} removed, back to the platform policy (actor={})",
                workspaceId, normalized, actorId);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * The effective workflow of one doc type in this workspace.
     *
     * <p>Mirrors the chain {@code GovernancePolicyService} evaluates at
     * runtime. It is used to report the previous value in the audit
     * trail and to fill the {@code platformWorkflow} /
     * {@code workspaceWorkflow} columns of the response.
     */
    private Workflow effectiveWorkflow(UUID workspaceId, String normalizedDocType) {
        WorkspaceDocTypePolicy row = repository
                .findByWorkspaceIdAndDocType(workspaceId, normalizedDocType)
                .orElse(null);
        if (row != null) {
            return row.getWorkflow();
        }
        PlatformDocTypePolicy platformRow = platformDocTypePolicyRepository
                .findByDocType(normalizedDocType)
                .orElse(null);
        if (platformRow != null) {
            return platformRow.getWorkflow();
        }
        return fallbackWorkflow(workspaceId);
    }

    /**
     * The workflow implied by the workspace's {@code requireManagerApproval}
     * flag — the last step of the chain.
     */
    private Workflow fallbackWorkflow(UUID workspaceId) {
        var settings = workspaceSettingsRepository.findById(workspaceId).orElse(null);
        boolean required = settings != null
                ? settings.isRequireManagerApproval()
                : platformSettingsRepository.getSingleton().isDefaultRequireManagerApproval();
        return required ? Workflow.MANAGER_REVIEW : Workflow.AUTO_APPLY;
    }

    private WorkspaceDocTypePolicyResponse describe(UUID workspaceId, String docType,
                                                    WorkspaceDocTypePolicy row) {
        PlatformDocTypePolicy platformRow = platformDocTypePolicyRepository
                .findByDocType(docType)
                .orElse(null);
        return WorkspaceDocTypePolicyResponse.of(docType, row, platformRow,
                fallbackWorkflow(workspaceId));
    }

    /**
     * Normalise and validate a doc type.
     *
     * <p>Only the canonical {@link DocType} values are accepted: the
     * policy table is keyed by doc type, and a typo would otherwise
     * create a row that nothing ever reads.
     */
    private static String normalizeDocType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("docType is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!isKnownDocType(normalized)) {
            throw new BadRequestException("Unknown doc type: " + raw
                    + ". Must be one of the canonical documentation types.");
        }
        return normalized;
    }

    private static boolean isKnownDocType(String normalized) {
        for (DocType type : DocType.values()) {
            if (type.name().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static Workflow parseWorkflow(String raw) {
        try {
            return Workflow.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "Invalid workflow: " + raw + ". Must be AUTO_APPLY or MANAGER_REVIEW");
        }
    }
}
