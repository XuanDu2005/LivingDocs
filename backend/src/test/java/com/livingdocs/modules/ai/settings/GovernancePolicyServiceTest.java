package com.livingdocs.modules.ai.settings;

import com.livingdocs.common.exception.TooManyRequestsException;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;
import com.livingdocs.modules.admin.model.PlatformSettings;
import com.livingdocs.modules.admin.repository.PlatformDocTypePolicyRepository;
import com.livingdocs.modules.admin.repository.PlatformSettingsRepository;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the governance rules that decide whether a generated version
 * is published automatically, queued for a human, or refused outright.
 */
class GovernancePolicyServiceTest {

    private GovernancePolicyService service;
    private PlatformDocTypePolicyRepository docTypePolicyRepository;
    private PlatformSettingsRepository platformSettingsRepository;
    private WorkspaceSettingsRepository workspaceSettingsRepository;
    private DriftAlertRepository driftAlertRepository;
    private AiRateLimitService rateLimitService;
    private ConfidenceThresholdService confidenceThresholdService;

    private UUID workspaceId;
    private WorkspaceSettings workspaceSettings;

    @BeforeEach
    void setUp() {
        docTypePolicyRepository = mock(PlatformDocTypePolicyRepository.class);
        platformSettingsRepository = mock(PlatformSettingsRepository.class);
        workspaceSettingsRepository = mock(WorkspaceSettingsRepository.class);
        driftAlertRepository = mock(DriftAlertRepository.class);
        rateLimitService = mock(AiRateLimitService.class);
        confidenceThresholdService = mock(ConfidenceThresholdService.class);

        service = new GovernancePolicyService(
                docTypePolicyRepository,
                platformSettingsRepository,
                workspaceSettingsRepository,
                driftAlertRepository,
                rateLimitService,
                confidenceThresholdService);

        workspaceId = UUID.randomUUID();

        // Defaults: platform requires manager approval, no doc-type rows.
        when(docTypePolicyRepository.findByDocType(any())).thenReturn(Optional.empty());
        when(workspaceSettingsRepository.findById(any())).thenReturn(Optional.empty());
        when(platformSettingsRepository.getSingleton())
                .thenReturn(new PlatformSettings());
    }

    // -----------------------------------------------------------------
    // doc-type workflow
    // -----------------------------------------------------------------

    @Test
    void explicitDocTypePolicyBeatsWorkspaceDefault() {
        // The workspace demands manager approval, but README has an
        // explicit AUTO_APPLY policy — the specific rule must win.
        givenWorkspaceRequiresManagerApproval(true);
        givenDocTypePolicy("README", PlatformDocTypePolicy.Workflow.AUTO_APPLY);

        assertTrue(service.isAutoApply(workspaceId, "README"),
                "explicit AUTO_APPLY policy must override requireManagerApproval");
    }

    @Test
    void workspaceDefaultAppliesWhenNoDocTypePolicyExists() {
        givenWorkspaceRequiresManagerApproval(true);

        assertFalse(service.isAutoApply(workspaceId, "MODULE_GUIDE"),
                "without an explicit policy the workspace default governs");
    }

    @Test
    void workspaceWithoutManagerApprovalAutoApplies() {
        givenWorkspaceRequiresManagerApproval(false);

        assertTrue(service.isAutoApply(workspaceId, "MODULE_GUIDE"));
    }

    @Test
    void docTypeLookupIsCaseInsensitive() {
        givenDocTypePolicy("README", PlatformDocTypePolicy.Workflow.AUTO_APPLY);

        assertTrue(service.isAutoApply(workspaceId, "readme"));
        assertTrue(service.isAutoApply(workspaceId, "  ReadMe  "));
    }

    @Test
    void blankDocTypeFallsBackToWorkspaceDefault() {
        givenWorkspaceRequiresManagerApproval(true);

        assertFalse(service.isAutoApply(workspaceId, ""));
        assertFalse(service.isAutoApply(workspaceId, null));
    }

    @Test
    void workflowForPrefersExplicitPolicy() {
        givenDocTypePolicy("CHANGELOG", PlatformDocTypePolicy.Workflow.AUTO_APPLY);

        assertEquals(PlatformDocTypePolicy.Workflow.AUTO_APPLY,
                service.workflowFor(workspaceId, "CHANGELOG"));
    }

    // -----------------------------------------------------------------
    // confidence threshold
    // -----------------------------------------------------------------

    @Test
    void confidenceAtThresholdPasses() {
        givenConfidenceThreshold(0.70f);

        assertTrue(service.meetsConfidenceThreshold(workspaceId, 0.70f),
                "a result exactly at the threshold is confident enough");
    }

    @Test
    void confidenceBelowThresholdFails() {
        givenConfidenceThreshold(0.70f);

        assertFalse(service.meetsConfidenceThreshold(workspaceId, 0.69f));
    }

    @Test
    void missingConfidenceIsNeverTrusted() {
        givenConfidenceThreshold(0.0f);

        assertFalse(service.meetsConfidenceThreshold(workspaceId, null),
                "a generation without a confidence score must go to a human");
    }

    // -----------------------------------------------------------------
    // token budget
    // -----------------------------------------------------------------

    @Test
    void exhaustedBudgetRaisesTooManyRequests() {
        when(rateLimitService.checkBudget(any())).thenReturn(false);
        when(rateLimitService.getSummary(any()))
                .thenReturn(new AiRateLimitService.UsageSummary(1220L, 1220L, 1L, 1L));

        TooManyRequestsException ex = assertThrows(
                TooManyRequestsException.class,
                () -> service.requireBudget(workspaceId));
        assertTrue(ex.getMessage().contains("1220"),
                "the message should report the tokens already spent");
    }

    @Test
    void availableBudgetPasses() {
        when(rateLimitService.checkBudget(any())).thenReturn(true);

        service.requireBudget(workspaceId);
    }

    // -----------------------------------------------------------------
    // merge gate
    // -----------------------------------------------------------------

    @Test
    void warnPolicyNeverBlocks() {
        givenMergePolicy("WARN");
        givenDriftSeverityThreshold("MEDIUM");
        givenOpenAlerts(driftAlert(DriftSeverity.CRITICAL));

        var verdict = service.evaluateMergeGate(workspaceId, null);

        assertFalse(verdict.blocked(), "WARN policy must not block a merge");
        assertEquals(1, verdict.blockingAlertCount(),
                "the alert is still counted, it just does not block");
    }

    @Test
    void blockPolicyBlocksOnCriticalDrift() {
        givenMergePolicy("BLOCK");
        givenDriftSeverityThreshold("MEDIUM");
        givenOpenAlerts(driftAlert(DriftSeverity.CRITICAL));

        var verdict = service.evaluateMergeGate(workspaceId, null);

        assertTrue(verdict.blocked());
        assertEquals(1, verdict.blockingAlertIds().size());
    }

    @Test
    void blockPolicyIgnoresDriftBelowThreshold() {
        givenMergePolicy("BLOCK");
        givenDriftSeverityThreshold("HIGH");
        givenOpenAlerts(driftAlert(DriftSeverity.MEDIUM));

        var verdict = service.evaluateMergeGate(workspaceId, null);

        assertFalse(verdict.blocked(), "MEDIUM drift is below a HIGH threshold");
        assertEquals(0, verdict.blockingAlertCount());
    }

    @Test
    void resolvedAlertsDoNotBlock() {
        givenMergePolicy("BLOCK");
        givenDriftSeverityThreshold("MEDIUM");

        DriftAlert resolved = driftAlert(DriftSeverity.CRITICAL);
        resolved.setResolutionStatus(DriftResolution.FIXED);
        givenOpenAlerts(resolved);

        var verdict = service.evaluateMergeGate(workspaceId, null);

        assertFalse(verdict.blocked(), "a resolved alert is not an open alert");
    }

    @Test
    void blocksOnCriticalDriftReflectsThePolicy() {
        givenMergePolicy("BLOCK");
        assertTrue(service.blocksOnCriticalDrift(workspaceId));

        givenMergePolicy("WARN");
        assertFalse(service.blocksOnCriticalDrift(workspaceId));
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private void givenWorkspaceRequiresManagerApproval(boolean required) {
        settings().setRequireManagerApproval(required);
    }

    private void givenDocTypePolicy(String docType, PlatformDocTypePolicy.Workflow workflow) {
        when(docTypePolicyRepository.findByDocType(docType))
                .thenReturn(Optional.of(new PlatformDocTypePolicy(docType, workflow)));
    }

    private void givenConfidenceThreshold(float threshold) {
        when(confidenceThresholdService.resolve(any())).thenReturn(threshold);
    }

    private void givenMergePolicy(String policy) {
        settings().setMergePolicyCritical(policy);
    }

    private void givenDriftSeverityThreshold(String severity) {
        settings().setDriftSeverityThreshold(severity);
    }

    /**
     * A single {@link WorkspaceSettings} row shared by all the
     * {@code given*} helpers.
     *
     * <p>They deliberately share one object: creating a fresh row per
     * helper would make the last helper win and silently reset the
     * values the earlier ones set.
     */
    private WorkspaceSettings settings() {
        if (workspaceSettings == null) {
            workspaceSettings = new WorkspaceSettings(workspaceId);
            when(workspaceSettingsRepository.findById(workspaceId))
                    .thenReturn(Optional.of(workspaceSettings));
        }
        return workspaceSettings;
    }

    private void givenOpenAlerts(DriftAlert... alerts) {
        when(driftAlertRepository
                .findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(any(), any()))
                .thenReturn(List.of(alerts));
    }

    private static DriftAlert driftAlert(DriftSeverity severity) {
        DriftAlert alert = new DriftAlert(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                com.livingdocs.modules.drift.model.DriftKind.SEMANTIC, severity,
                "title", "description", "{}", "suggestion", 0.9f);
        return alert;
    }
}
