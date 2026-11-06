package com.livingdocs.modules.version;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The version-level rules that the review workflow depends on:
 * a draft can only be dismissed by the person who wrote it, a
 * rollback produces a <em>draft</em> (not a publishable version), and
 * publishing needs the publisher permission.
 */
class DocumentVersionServiceReviewTest {

    private DocumentVersionRepository versionRepository;
    private DocumentRepository documentRepository;
    private AuthorizationService authorizationService;
    private PermissionService permissionService;
    private AuditLogService auditLogService;
    private DocumentVersionService service;

    private UUID actorId;
    private UUID otherId;
    private UUID documentId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        versionRepository = mock(DocumentVersionRepository.class);
        documentRepository = mock(DocumentRepository.class);
        authorizationService = mock(AuthorizationService.class);
        permissionService = mock(PermissionService.class);
        auditLogService = mock(AuditLogService.class);

        service = new DocumentVersionService(
                versionRepository,
                documentRepository,
                mock(WorkspaceService.class),
                mock(CommitRepository.class),
                mock(PullRequestRepository.class),
                authorizationService,
                permissionService,
                auditLogService);

        // The repository hands back whatever it was given.
        when(versionRepository.save(any(DocumentVersion.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        actorId = UUID.randomUUID();
        otherId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getWorkspaceId()).thenReturn(workspaceId);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    }

    private DocumentVersion givenVersion(VersionStatus status, UUID author, Integer number) {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getDocumentId()).thenReturn(documentId);
        when(version.getStatus()).thenReturn(status);
        when(version.getActorUserId()).thenReturn(author);
        when(version.getVersionNumber()).thenReturn(number);
        when(version.getBodyMarkdown()).thenReturn("# Body\n");
        when(versionRepository.findByDocumentIdAndVersionNumber(documentId, number))
                .thenReturn(Optional.of(version));
        return version;
    }

    /**
     * A real entity rather than a mock: the dismissal test has to see
     * the status <em>change</em>, and a mock's stubbed getter would
     * keep returning the old value.
     */
    private DocumentVersion givenRealVersion(VersionStatus status, UUID author, Integer number) {
        DocumentVersion version = new DocumentVersion(
                documentId, number, "# Body\n", "summary",
                ActorRole.DEVELOPER, author,
                null, null, null,
                status, null);
        when(versionRepository.findByDocumentIdAndVersionNumber(documentId, number))
                .thenReturn(Optional.of(version));
        return version;
    }

    /**
     * The author may throw away their own draft.
     */
    @Test
    void theAuthorMayDismissTheirOwnDraft() {
        DocumentVersion draft = givenRealVersion(VersionStatus.DRAFT, actorId, 2);

        var result = service.dismissVersion(actorId, documentId, 2);

        assertEquals(VersionStatus.REJECTED, result.getStatus());
        assertEquals(draft.getId(), result.getId());
    }

    /**
     * A Staff reviewer may not throw away somebody else's draft. The
     * permission matrix grants STAFF the dismiss capability, but the
     * capability is scoped to drafts the caller authored — a reviewer
     * who could discard other people's work would be able to stall the
     * whole review pipeline.
     */
    @Test
    void aReviewerMayNotDismissSomebodyElsesDraft() {
        givenVersion(VersionStatus.DRAFT, otherId, 2);

        assertThrows(ForbiddenException.class,
                () -> service.dismissVersion(actorId, documentId, 2));
    }

    /**
     * Only a draft can be dismissed — a published version is history.
     */
    @Test
    void onlyDraftsCanBeDismissed() {
        givenVersion(VersionStatus.PUBLISHED, actorId, 2);

        assertThrows(BadRequestException.class,
                () -> service.dismissVersion(actorId, documentId, 2));
    }

    /**
     * A rollback is a correction, not a publication: the new version
     * starts life as a draft and still has to go through review.
     */
    @Test
    void aRollbackProducesADraft() {
        DocumentVersion target = givenVersion(VersionStatus.PUBLISHED, otherId, 4);
        when(target.getSourceCommitSha()).thenReturn("abc1234");
        when(target.getTemplateId()).thenReturn(null);
        when(versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(Optional.of(target));

        DocumentVersion rollback = service.rollback(actorId, documentId, 4);

        assertEquals(VersionStatus.DRAFT, rollback.getStatus(),
                "a rollback is a correction and must still be reviewed");
        assertEquals(5, rollback.getVersionNumber());
        assertEquals("abc1234", rollback.getSourceCommitSha(),
                "the rollback keeps the source commit of the version it copies");
    }

    /**
     * A rollback cannot target a version that was never approved.
     */
    @Test
    void aRollbackRejectsUnapprovedVersions() {
        givenVersion(VersionStatus.DRAFT, otherId, 4);

        assertThrows(BadRequestException.class,
                () -> service.rollback(actorId, documentId, 4));
    }

    /**
     * Publishing needs the publisher permission — the guard runs before
     * anything is written.
     */
    @Test
    void publishingRequiresThePublisherPermission() {
        givenVersion(VersionStatus.APPROVED, otherId, 3);

        // The permission service is a mock, so it never throws; the
        // guard is asserted by verifying it was consulted at all.
        service.publishVersion(actorId, documentId, 3);

        org.mockito.Mockito.verify(authorizationService)
                .requireDocumentPublisher(actorId, workspaceId);
    }

    /**
     * Only an approved version can be published.
     */
    @Test
    void onlyApprovedVersionsCanBePublished() {
        givenVersion(VersionStatus.DRAFT, otherId, 3);

        assertThrows(BadRequestException.class,
                () -> service.publishVersion(actorId, documentId, 3));
    }

    /**
     * The rollback is attributed to the role the actor actually holds.
     */
    @Test
    void aRollbackIsAttributedToTheActorsRole() {
        DocumentVersion target = givenVersion(VersionStatus.PUBLISHED, otherId, 4);
        when(target.getSourceCommitSha()).thenReturn(null);
        when(target.getTemplateId()).thenReturn(null);
        when(versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(Optional.of(target));
        when(authorizationService.platformRoles(actorId))
                .thenReturn(java.util.Set.of(AuthorizationService.ROLE_MANAGER));

        DocumentVersion rollback = service.rollback(actorId, documentId, 4);

        assertEquals(ActorRole.MANAGER, rollback.getActorRole());
    }

    /**
     * Every change that touches a document has to leave a trace in its
     * audit trail.
     *
     * <p>The requirement is the "complete audit trail of a document
     * (AI-generated, auto-updated, staff-edited)". A publish that
     * leaves no entry makes the trail incomplete — and a publish is
     * precisely the moment a reader of the trail cares about most.
     */
    @Test
    void publishingIsRecordedInTheAuditTrail() {
        givenVersion(VersionStatus.APPROVED, otherId, 3);

        service.publishVersion(actorId, documentId, 3);

        org.mockito.Mockito.verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq(actorId),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("document.version.published"),
                org.mockito.ArgumentMatchers.eq("document_version"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq(documentId),
                org.mockito.ArgumentMatchers.anyMap());
    }

    /**
     * A human edit is one of the three things the trail has to show
     * ("staff-edited"), so it must leave an entry too.
     */
    @Test
    void aHumanEditIsRecordedInTheAuditTrail() {
        when(versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(Optional.empty());
        when(authorizationService.platformRoles(actorId))
                .thenReturn(java.util.Set.of(AuthorizationService.ROLE_DEVELOPER));

        service.createHumanVersion(actorId, documentId, "# Body\n", "fixed a typo", null);

        org.mockito.Mockito.verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq(actorId),
                org.mockito.ArgumentMatchers.eq("DEVELOPER"),
                org.mockito.ArgumentMatchers.eq("document.version.created"),
                org.mockito.ArgumentMatchers.eq("document_version"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq(documentId),
                org.mockito.ArgumentMatchers.anyMap());
    }
}
