package com.livingdocs.modules.cicd.service;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.ai.settings.GovernancePolicyService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubPullRequestFile;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.DriftDetectionOnPullRequestService;
import com.livingdocs.modules.github.service.DocumentAutoUpdateService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the CI-facing merge gate.
 *
 * <p>The behaviour under test is the whole point of the fix: the gate
 * reads the workspace's own policy, scans for the drift the pull
 * request introduces, and answers. The workflow's decision is never its
 * own.
 */
class MergeGateServiceTest {

    private MergeGateService service;

    private GovernancePolicyService governancePolicyService;
    private RepositoryRepository repositoryRepository;
    private PullRequestRepository pullRequestRepository;
    private DriftDetectionOnPullRequestService driftDetectionService;
    private DocumentAutoUpdateService autoUpdateService;
    private GithubClient githubClient;
    private WorkspaceService workspaceService;
    private AuditLogService auditLogService;

    private UUID workspaceId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        governancePolicyService = mock(GovernancePolicyService.class);
        repositoryRepository = mock(RepositoryRepository.class);
        pullRequestRepository = mock(PullRequestRepository.class);
        driftDetectionService = mock(DriftDetectionOnPullRequestService.class);
        autoUpdateService = mock(DocumentAutoUpdateService.class);
        githubClient = mock(GithubClient.class);
        workspaceService = mock(WorkspaceService.class);
        auditLogService = mock(AuditLogService.class);

        service = new MergeGateService(
                governancePolicyService,
                repositoryRepository,
                pullRequestRepository,
                driftDetectionService,
                autoUpdateService,
                githubClient,
                workspaceService,
                auditLogService);

        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        Repository repo = Repository.create(
                workspaceId, 42L, "acme", "docs", "main", null, null, false, UUID.randomUUID());
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repo));

        when(pullRequestRepository.findByRepositoryIdAndGithubPrNumber(any(), anyLong()))
                .thenReturn(Optional.empty());
        when(autoUpdateService.resolveAccessToken(workspaceId)).thenReturn("gh-token");
        when(githubClient.listPullRequestFiles(any(), any(), any(), anyLong()))
                .thenReturn(List.of(new GithubPullRequestFile(
                        "src/App.java", "modified", "patch", 10L, 2L, 12L)));
        when(driftDetectionService.detect(any(), anySet(), any(), any(), any(), any()))
                .thenReturn(2);
        when(governancePolicyService.evaluateMergeGate(eq(workspaceId), any()))
                .thenReturn(new GovernancePolicyService.MergeVerdict(
                        false, "WARN", DriftSeverity.MEDIUM, 0, List.of(),
                        "No open drift alerts at or above severity MEDIUM"));
    }

    // -----------------------------------------------------------------
    // the verdict
    // -----------------------------------------------------------------

    @Test
    void verdictMirrorsTheWorkspacePolicy() {
        when(governancePolicyService.evaluateMergeGate(eq(workspaceId), any()))
                .thenReturn(new GovernancePolicyService.MergeVerdict(
                        true, "BLOCK", DriftSeverity.HIGH, 2,
                        List.of(UUID.randomUUID(), UUID.randomUUID()),
                        "2 open drift alert(s) at or above severity HIGH — merge blocked by policy BLOCK"));

        var verdict = service.evaluate(workspaceId, repositoryId, 42L);

        assertTrue(verdict.blocked(), "a BLOCK policy with open alerts must block");
        assertEquals("BLOCK", verdict.policy());
        assertEquals("HIGH", verdict.severityThreshold());
        assertEquals(2, verdict.blockingAlertCount());
        assertEquals(42L, verdict.pullRequestNumber());
    }

    @Test
    void warnPolicyNeverBlocks() {
        when(governancePolicyService.evaluateMergeGate(eq(workspaceId), any()))
                .thenReturn(new GovernancePolicyService.MergeVerdict(
                        false, "WARN", DriftSeverity.MEDIUM, 3, List.of(),
                        "3 open drift alert(s) — merge allowed (policy WARN)"));

        var verdict = service.evaluate(workspaceId, repositoryId, 42L);

        assertFalse(verdict.blocked(), "WARN policy must not block");
        assertEquals(3, verdict.blockingAlertCount(), "the alerts are still counted");
    }

    // -----------------------------------------------------------------
    // the scan
    // -----------------------------------------------------------------

    @Test
    void scanRunsBeforeTheGateIsEvaluated() {
        service.evaluate(workspaceId, repositoryId, 42L);

        verify(driftDetectionService).detect(any(), anySet(), eq("main"), eq("main"),
                any(), eq("gh-token"));
    }

    @Test
    void scanSkippedWithoutAGitHubConnection() {
        when(autoUpdateService.resolveAccessToken(workspaceId)).thenReturn(null);

        var verdict = service.evaluate(workspaceId, repositoryId, 42L);

        verify(driftDetectionService, never()).detect(any(), anySet(), any(), any(), any(), any());
        assertEquals(0, verdict.alertsFiled());
    }

    @Test
    void aFailedScanDoesNotBlockTheMerge() {
        when(driftDetectionService.detect(any(), anySet(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("GitHub is down"));

        var verdict = service.evaluate(workspaceId, repositoryId, 42L);

        assertFalse(verdict.blocked(), "a failed scan must not manufacture a block");
        assertNotNull(verdict.scanNote());
        assertTrue(verdict.scanNote().contains("GitHub is down"));
    }

    // -----------------------------------------------------------------
    // workspace isolation
    // -----------------------------------------------------------------

    @Test
    void aTokenCannotActOnAnotherWorkspacesRepository() {
        Repository foreign = Repository.create(
                UUID.randomUUID(), 42L, "acme", "docs", "main", null, null, false,
                UUID.randomUUID());
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(foreign));

        assertThrows(ForbiddenException.class,
                () -> service.evaluate(workspaceId, repositoryId, 42L));
    }

    @Test
    void unknownRepositoryIsRejected() {
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.evaluate(workspaceId, repositoryId, 42L));
    }

    // -----------------------------------------------------------------
    // audit trail
    // -----------------------------------------------------------------

    @Test
    void everyEvaluationIsAudited() {
        service.evaluate(workspaceId, repositoryId, 42L);

        verify(auditLogService).record(
                org.mockito.ArgumentMatchers.isNull(),
                eq("CI"),
                eq("merge.gate.evaluate"),
                eq("pull_request"),
                eq("42"),
                eq(workspaceId),
                any());
    }
}
