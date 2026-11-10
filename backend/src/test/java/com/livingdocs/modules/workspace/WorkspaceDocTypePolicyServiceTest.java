package com.livingdocs.modules.workspace;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;
import com.livingdocs.modules.admin.model.PlatformSettings;
import com.livingdocs.modules.admin.repository.PlatformDocTypePolicyRepository;
import com.livingdocs.modules.admin.repository.PlatformSettingsRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.template.model.DocType;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceDocTypePolicyRequest;
import com.livingdocs.modules.workspace.model.WorkspaceDocTypePolicy;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import com.livingdocs.modules.workspace.repository.WorkspaceDocTypePolicyRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import com.livingdocs.modules.workspace.service.WorkspaceDocTypePolicyService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the per-workspace doc-type policy overrides.
 *
 * <p>The point of the feature is that a workspace manager can decide, for
 * their own workspace, which documentation types need an approval —
 * without the platform administrator having to change a rule that applies
 * to every other workspace.
 */
class WorkspaceDocTypePolicyServiceTest {

    private WorkspaceDocTypePolicyService service;
    private WorkspaceDocTypePolicyRepository repository;
    private PlatformDocTypePolicyRepository platformDocTypePolicyRepository;
    private WorkspaceSettingsRepository workspaceSettingsRepository;
    private PlatformSettingsRepository platformSettingsRepository;
    private WorkspaceService workspaceService;
    private AuditLogService auditLogService;

    private UUID workspaceId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        repository = mock(WorkspaceDocTypePolicyRepository.class);
        platformDocTypePolicyRepository = mock(PlatformDocTypePolicyRepository.class);
        workspaceSettingsRepository = mock(WorkspaceSettingsRepository.class);
        platformSettingsRepository = mock(PlatformSettingsRepository.class);
        workspaceService = mock(WorkspaceService.class);
        auditLogService = mock(AuditLogService.class);

        service = new WorkspaceDocTypePolicyService(
                repository,
                platformDocTypePolicyRepository,
                workspaceSettingsRepository,
                platformSettingsRepository,
                workspaceService,
                auditLogService);

        workspaceId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        when(repository.findAllByWorkspaceIdOrderByDocTypeAsc(any()))
                .thenReturn(List.of());
        when(repository.findByWorkspaceIdAndDocType(any(), anyString()))
                .thenReturn(Optional.empty());
        // JpaRepository.save returns the persisted entity; the mock has
        // to echo the argument back or the caller gets null.
        when(repository.save(any(WorkspaceDocTypePolicy.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(platformDocTypePolicyRepository.findByDocType(anyString()))
                .thenReturn(Optional.empty());
        when(workspaceSettingsRepository.findById(any())).thenReturn(Optional.empty());
        when(platformSettingsRepository.getSingleton()).thenReturn(new PlatformSettings());
    }

    // -----------------------------------------------------------------
    // reading
    // -----------------------------------------------------------------

    @Test
    void listShowsEveryCanonicalDocType() {
        var rows = service.list(workspaceId, actorId);

        // The count is derived from the enum rather than hard-coded: a
        // new documentation type has to appear in the policy panel, and
        // a hard-coded number would have to be bumped by hand every time
        // one is added.
        assertEquals(DocType.values().length, rows.size(),
                "every canonical doc type must be listed so an inherited "
                        + "policy is visible as inherited");
        assertTrue(rows.stream().allMatch(r -> r.scope().equals("DEFAULT")),
                "without any row the scope is DEFAULT");
    }

    /**
     * The onboarding guide is a canonical doc type like every other —
     * it has its own row in the policy panel.
     */
    @Test
    void theOnboardingGuideIsListedAsACanonicalDocType() {
        var rows = service.list(workspaceId, actorId);

        assertTrue(rows.stream().anyMatch(r -> r.docType().equals("ONBOARDING_GUIDE")),
                "the onboarding guide must be listed so its policy can be overridden");
    }

    @Test
    void listShowsTheWorkspaceOverrideAsTheScope() {
        givenWorkspaceRow("README", PlatformDocTypePolicy.Workflow.MANAGER_REVIEW);
        when(repository.findAllByWorkspaceIdOrderByDocTypeAsc(workspaceId))
                .thenReturn(List.of(workspaceRow("README",
                        PlatformDocTypePolicy.Workflow.MANAGER_REVIEW)));

        var readme = service.list(workspaceId, actorId).stream()
                .filter(r -> r.docType().equals("README"))
                .findFirst()
                .orElseThrow();

        assertEquals("WORKSPACE", readme.scope());
        assertEquals("MANAGER_REVIEW", readme.workflow());
        assertEquals("MANAGER_REVIEW", readme.workspaceWorkflow());
    }

    @Test
    void listFallsBackToThePlatformPolicy() {
        when(platformDocTypePolicyRepository.findByDocType("README"))
                .thenReturn(Optional.of(new PlatformDocTypePolicy(
                        "README", PlatformDocTypePolicy.Workflow.AUTO_APPLY)));

        var readme = service.list(workspaceId, actorId).stream()
                .filter(r -> r.docType().equals("README"))
                .findFirst()
                .orElseThrow();

        assertEquals("PLATFORM", readme.scope());
        assertEquals("AUTO_APPLY", readme.workflow());
        assertNull(readme.workspaceWorkflow());
    }

    @Test
    void listRequiresMembership() {
        org.mockito.Mockito.doThrow(new ForbiddenException("not a member"))
                .when(workspaceService).requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        assertThrows(ForbiddenException.class, () -> service.list(workspaceId, actorId));
    }

    // -----------------------------------------------------------------
    // writing
    // -----------------------------------------------------------------

    @Test
    void upsertCreatesTheRowWhenThereIsNone() {
        var response = service.upsert(workspaceId, actorId, "README",
                new UpdateWorkspaceDocTypePolicyRequest("MANAGER_REVIEW"));

        verify(repository).save(any(WorkspaceDocTypePolicy.class));
        assertEquals("WORKSPACE", response.scope());
        assertEquals("MANAGER_REVIEW", response.workflow());
    }

    @Test
    void upsertChangesTheExistingRow() {
        givenWorkspaceRow("README", PlatformDocTypePolicy.Workflow.AUTO_APPLY);

        var response = service.upsert(workspaceId, actorId, "README",
                new UpdateWorkspaceDocTypePolicyRequest("MANAGER_REVIEW"));

        assertEquals("MANAGER_REVIEW", response.workflow());
        verify(repository).save(any(WorkspaceDocTypePolicy.class));
    }

    @Test
    void upsertNormalisesTheDocType() {
        var response = service.upsert(workspaceId, actorId, "  readme ",
                new UpdateWorkspaceDocTypePolicyRequest("AUTO_APPLY"));

        assertEquals("README", response.docType());
    }

    @Test
    void upsertRejectsAnUnknownDocType() {
        assertThrows(BadRequestException.class, () -> service.upsert(
                workspaceId, actorId, "NOT_A_DOC_TYPE",
                new UpdateWorkspaceDocTypePolicyRequest("AUTO_APPLY")));
    }

    @Test
    void upsertRejectsABlankDocType() {
        assertThrows(BadRequestException.class, () -> service.upsert(
                workspaceId, actorId, "  ",
                new UpdateWorkspaceDocTypePolicyRequest("AUTO_APPLY")));
    }

    @Test
    void upsertRejectsAnInvalidWorkflow() {
        assertThrows(BadRequestException.class, () -> service.upsert(
                workspaceId, actorId, "README",
                new UpdateWorkspaceDocTypePolicyRequest("WHATEVER")));
    }

    @Test
    void upsertRequiresTheManagerRole() {
        org.mockito.Mockito.doThrow(new ForbiddenException("not a manager"))
                .when(workspaceService).requireDocumentationGovernance(actorId, workspaceId);

        assertThrows(ForbiddenException.class, () -> service.upsert(
                workspaceId, actorId, "README",
                new UpdateWorkspaceDocTypePolicyRequest("AUTO_APPLY")));
    }

    @Test
    void deleteRemovesTheOverride() {
        givenWorkspaceRow("README", PlatformDocTypePolicy.Workflow.AUTO_APPLY);

        service.delete(workspaceId, actorId, "README");

        verify(repository).delete(any(WorkspaceDocTypePolicy.class));
    }

    @Test
    void deleteFailsWhenThereIsNoOverride() {
        assertThrows(NotFoundException.class,
                () -> service.delete(workspaceId, actorId, "README"));
    }

    @Test
    void deleteRequiresTheManagerRole() {
        org.mockito.Mockito.doThrow(new ForbiddenException("not a manager"))
                .when(workspaceService).requireDocumentationGovernance(actorId, workspaceId);

        assertThrows(ForbiddenException.class,
                () -> service.delete(workspaceId, actorId, "README"));
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private void givenWorkspaceRow(String docType, PlatformDocTypePolicy.Workflow workflow) {
        when(repository.findByWorkspaceIdAndDocType(eq(workspaceId), eq(docType)))
                .thenReturn(Optional.of(workspaceRow(docType, workflow)));
    }

    private static WorkspaceDocTypePolicy workspaceRow(String docType,
                                                        PlatformDocTypePolicy.Workflow workflow) {
        return new WorkspaceDocTypePolicy(UUID.randomUUID(), docType, workflow);
    }
}

