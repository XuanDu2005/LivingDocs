package com.livingdocs.modules.drift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.dto.IngestDriftAlertRequest;
import com.livingdocs.modules.drift.dto.ResolveDriftAlertRequest;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftKind;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.drift.service.DriftAlertService;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drift alert service and the capability that guards it.
 *
 * <p>Resolving a drift alert used to require workspace membership
 * alone, so every member of a workspace could accept, dismiss or mark
 * fixed any alert in it. These tests pin the tightening: the resolve
 * path asks for the {@code DRIFT_RESOLVE} capability, while the read
 * and ingest paths stay open to every member.
 */
class DriftAlertServiceTest {

    private DriftAlertRepository driftRepository;
    private DocumentRepository documentRepository;
    private PullRequestRepository pullRequestRepository;
    private RepositoryRepository repositoryRepository;
    private WorkspaceService workspaceService;
    private AuthorizationService authorizationService;
    private DriftAlertService service;

    private UUID workspaceId;
    private UUID actorId;
    private UUID alertId;

    @BeforeEach
    void setUp() {
        driftRepository = mock(DriftAlertRepository.class);
        documentRepository = mock(DocumentRepository.class);
        pullRequestRepository = mock(PullRequestRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        workspaceService = mock(WorkspaceService.class);
        authorizationService = mock(AuthorizationService.class);

        service = new DriftAlertService(
                driftRepository,
                documentRepository,
                pullRequestRepository,
                repositoryRepository,
                workspaceService,
                authorizationService,
                new ObjectMapper());

        workspaceId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        alertId = UUID.randomUUID();

        // The alert is built into a local variable first. Calling
        // `anAlert()` inside the argument of another `when(...)` would
        // nest one stubbing inside another, which Mockito rejects with
        // an UnfinishedStubbingException — the inner `when(...)` calls
        // would never see a completed `thenReturn(...)`.
        DriftAlert alert = anAlert();
        when(driftRepository.findById(alertId)).thenReturn(Optional.of(alert));
        when(driftRepository.findAllByWorkspaceIdOrderByDetectedAtDesc(workspaceId))
                .thenReturn(List.of(alert));

        // The repository hands back whatever it was given, the way the
        // JPA implementation does.
        when(driftRepository.save(any(DriftAlert.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * An open drift alert of the workspace under test.
     */
    private DriftAlert anAlert() {
        DriftAlert alert = mock(DriftAlert.class);
        when(alert.getId()).thenReturn(alertId);
        when(alert.getWorkspaceId()).thenReturn(workspaceId);
        when(alert.getDocumentId()).thenReturn(UUID.randomUUID());
        when(alert.getRepositoryId()).thenReturn(UUID.randomUUID());
        when(alert.getDriftKind()).thenReturn(DriftKind.SIGNATURE);
        when(alert.getSeverity()).thenReturn(DriftSeverity.HIGH);
        when(alert.getTitle()).thenReturn("Signature changed");
        when(alert.getDescription()).thenReturn("create() gained a parameter");
        when(alert.getEvidence()).thenReturn("{}");
        when(alert.getResolutionStatus()).thenReturn(DriftResolution.OPEN);
        return alert;
    }

    // =================================================================
    // Resolving
    // =================================================================

    /**
     * The core of the tightening: resolving asks for the resolve
     * capability of the workspace the alert belongs to.
     */
    @Test
    void resolvingAsksForTheResolveCapability() {
        service.resolve(actorId, alertId, new ResolveDriftAlertRequest(DriftResolution.ACCEPTED));

        verify(authorizationService).requireDriftResolver(actorId, workspaceId);
    }

    /**
     * The read path stays open to every member: everybody has to see
     * that the documentation they rely on has drifted.
     */
    @Test
    void listingDoesNotAskForTheResolveCapability() {
        service.listForWorkspace(actorId, workspaceId, null, null, null);

        verify(authorizationService, never()).requireDriftResolver(any(), any());
    }

    /**
     * The ingest path stays open too: the AI service and the
     * pull-request path file alerts on behalf of a member.
     */
    @Test
    void ingestingDoesNotAskForTheResolveCapability() {
        service.ingest(actorId, workspaceId, new IngestDriftAlertRequest(
                UUID.randomUUID(), null, UUID.randomUUID(),
                DriftKind.SEMANTIC, DriftSeverity.LOW,
                "Title", "Description", "{}", null, 0.9f));

        verify(authorizationService, never()).requireDriftResolver(any(), any());
    }

    /**
     * A resolution of OPEN is not a resolution: the alert would stay in
     * the queue while claiming to have been closed.
     */
    @Test
    void openIsNotAResolution() {
        assertThrows(BadRequestException.class,
                () -> service.resolve(actorId, alertId, new ResolveDriftAlertRequest(DriftResolution.OPEN)));
    }

    /**
     * A member who may not resolve an alert must not be able to close
     * one — the guard refuses before the alert is touched.
     */
    @Test
    void aMemberWithoutTheCapabilityCannotResolve() {
        doThrow(new ForbiddenException(
                        "This action requires one of these permissions: DRIFT_RESOLVE"))
                .when(authorizationService).requireDriftResolver(eq(actorId), eq(workspaceId));

        assertThrows(ForbiddenException.class,
                () -> service.resolve(actorId, alertId, new ResolveDriftAlertRequest(DriftResolution.DISMISSED)));
    }

    /**
     * The resolution is recorded on the alert: the status, the moment
     * and the person who made the call.
     */
    @Test
    void theResolutionIsRecordedOnTheAlert() {
        DriftAlert alert = anAlert();
        when(driftRepository.findById(alertId)).thenReturn(Optional.of(alert));

        service.resolve(actorId, alertId, new ResolveDriftAlertRequest(DriftResolution.FIXED));

        verify(alert).setResolutionStatus(DriftResolution.FIXED);
        verify(alert).setResolvedBy(actorId);
        verify(alert).setResolvedAt(any());
    }
}
