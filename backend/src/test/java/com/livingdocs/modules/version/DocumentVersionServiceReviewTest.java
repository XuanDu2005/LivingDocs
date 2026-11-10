package com.livingdocs.modules.version;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.service.TemplateComplianceEvaluator;
import com.livingdocs.modules.template.service.TemplateSchema;
import com.livingdocs.modules.template.service.TemplateSchemaResolver;
import com.livingdocs.modules.template.service.TemplateRenderer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private TemplateSchemaResolver templateResolver;
    private TemplateComplianceEvaluator complianceEvaluator;
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
        templateResolver = mock(TemplateSchemaResolver.class);
        complianceEvaluator = new TemplateComplianceEvaluator(
                new TemplateSchema(new ObjectMapper()), new ObjectMapper());

        service = new DocumentVersionService(
                versionRepository,
                documentRepository,
                mock(WorkspaceService.class),
                mock(CommitRepository.class),
                mock(PullRequestRepository.class),
                authorizationService,
                permissionService,
                auditLogService,
                templateResolver,
                complianceEvaluator,
                new TemplateRenderer());

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

        // By default the document has no template, so the publish guard
        // has nothing to check.
        when(templateResolver.resolveTemplate(any(Document.class), any()))
                .thenReturn(null);
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

    // -----------------------------------------------------------------
    // Template compliance on publish
    // -----------------------------------------------------------------

    /**
     * A template with the given JSON body.
     */
    private DocTemplate templateWith(String bodyJson) {
        return new DocTemplate(
                workspaceId,
                "API reference",
                "api-reference",
                null,
                "API_REFERENCE",
                1,
                bodyJson,
                false,
                actorId);
    }

    /**
     * Make the resolved template of the document the given one.
     */
    private void givenTemplate(DocTemplate template) {
        when(templateResolver.resolveTemplate(any(Document.class), any()))
                .thenReturn(template);
    }

    /**
     * A version whose body is the given markdown.
     */
    private DocumentVersion givenVersionWithBody(VersionStatus status, Integer number, String body) {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getDocumentId()).thenReturn(documentId);
        when(version.getStatus()).thenReturn(status);
        when(version.getVersionNumber()).thenReturn(number);
        when(version.getBodyMarkdown()).thenReturn(body);
        when(versionRepository.findByDocumentIdAndVersionNumber(documentId, number))
                .thenReturn(Optional.of(version));
        return version;
    }

    // -----------------------------------------------------------------
    // Finding the previous published version
    // -----------------------------------------------------------------

    /**
     * The version a bulk rollback restores a document to: the version
     * that was published before the current head.
     *
     * <p>The head is {@code PUBLISHED} and the version it replaced is
     * {@code SUPERSEDED} — a superseded version <em>was</em> published,
     * so it is what "roll back to the previous published version" means.
     */
    @Test
    void thePreviousPublishedVersionIsTheOneBelowTheHead() {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);

        DocumentVersion head = givenVersion(VersionStatus.PUBLISHED, otherId, 5);
        DocumentVersion previous = givenVersion(VersionStatus.SUPERSEDED, otherId, 3);
        // The id is read before the stubbing call: reading a mock inside
        // a `thenReturn(...)` argument leaves the stubbing unfinished.
        UUID headId = head.getId();
        when(document.getHeadVersionId()).thenReturn(headId);
        when(versionRepository.findAllByDocumentIdAndStatusInOrderByVersionNumberDesc(
                documentId,
                java.util.Set.of(VersionStatus.PUBLISHED, VersionStatus.APPROVED,
                        VersionStatus.SUPERSEDED)))
                .thenReturn(java.util.List.of(head, previous));

        DocumentVersion target = service.findPreviousPublishedVersion(document);

        assertEquals(previous, target,
                "the target is the version that was published before the head");
    }

    /**
     * A document that was never published has no "previous published
     * version" — but the newest approved version is the closest thing
     * it has.
     */
    @Test
    void thePreviousPublishedVersionFallsBackToTheNewestApproved() {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);

        DocumentVersion approved = givenVersion(VersionStatus.APPROVED, otherId, 2);
        when(document.getHeadVersionId()).thenReturn(null);
        when(versionRepository.findAllByDocumentIdAndStatusInOrderByVersionNumberDesc(
                documentId,
                java.util.Set.of(VersionStatus.PUBLISHED, VersionStatus.APPROVED,
                        VersionStatus.SUPERSEDED)))
                .thenReturn(java.util.List.of(approved));

        DocumentVersion target = service.findPreviousPublishedVersion(document);

        assertEquals(approved, target);
    }

    /**
     * A document with no restorable version at all has nothing to roll
     * back to.
     */
    @Test
    void aDocumentWithoutAnyRestorableVersionHasNoTarget() {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getHeadVersionId()).thenReturn(null);
        when(versionRepository.findAllByDocumentIdAndStatusInOrderByVersionNumberDesc(
                documentId,
                java.util.Set.of(VersionStatus.PUBLISHED, VersionStatus.APPROVED,
                        VersionStatus.SUPERSEDED)))
                .thenReturn(java.util.List.of());

        assertEquals(null, service.findPreviousPublishedVersion(document));
    }

    /**
     * A rollback to a superseded version is allowed: the version was
     * published, it is just not the head any more.
     */
    @Test
    void aRollbackToASupersededVersionIsAllowed() {
        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, otherId, 3);
        when(target.getSourceCommitSha()).thenReturn("abc1234");
        when(target.getTemplateId()).thenReturn(null);
        when(versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(Optional.of(target));

        DocumentVersion rollback = service.rollback(actorId, documentId, 3);

        assertEquals(VersionStatus.DRAFT, rollback.getStatus());
        assertEquals(4, rollback.getVersionNumber());
    }

    /**
     * The headline rule: a version that does not follow the template it
     * was generated from cannot become the published head.
     *
     * <p>The template is strict and asks for three sections; the body
     * only carries two of them.
     */
    @Test
    void aVersionThatDoesNotFollowItsTemplateCannotBePublished() {
        givenTemplate(templateWith("""
                {
                  "strict": true,
                  "sections": [
                    { "id": "overview", "heading": "Overview", "required": true },
                    { "id": "api-surface", "heading": "API surface", "required": true },
                    { "id": "testing", "heading": "Testing", "required": true }
                  ]
                }
                """));
        givenVersionWithBody(VersionStatus.APPROVED, 3, """
                # Overview

                text

                # API surface

                text
                """);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.publishVersion(actorId, documentId, 3));

        assertTrue(ex.getMessage().contains("Testing"),
                "the message names the section that is missing: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("API reference"),
                "the message names the template: " + ex.getMessage());
    }

    /**
     * A version that follows the template is published as before — the
     * guard must not get in the way of a well-formed document.
     */
    @Test
    void aVersionThatFollowsItsTemplateCanBePublished() {
        givenTemplate(templateWith("""
                {
                  "strict": true,
                  "sections": [
                    { "id": "overview", "heading": "Overview", "required": true },
                    { "id": "api-surface", "heading": "API surface", "required": true }
                  ]
                }
                """));
        givenVersionWithBody(VersionStatus.APPROVED, 3, """
                # Overview

                text

                # API surface

                text
                """);

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
     * A template that is not strict only produces a warning — the
     * publication goes through, and the trail records that the
     * template was not followed.
     */
    @Test
    void aNonStrictTemplateDoesNotBlockThePublication() {
        givenTemplate(templateWith("""
                {
                  "strict": false,
                  "sections": [
                    { "id": "overview", "heading": "Overview", "required": true }
                  ]
                }
                """));
        givenVersionWithBody(VersionStatus.APPROVED, 3, "# Something else\n\ntext\n");

        service.publishVersion(actorId, documentId, 3);

        org.mockito.Mockito.verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq(actorId),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("document.version.published_noncompliant"),
                org.mockito.ArgumentMatchers.eq("document_version"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq(documentId),
                org.mockito.ArgumentMatchers.anyMap());
    }

    /**
     * The wrong order of the sections is a stylistic matter — it never
     * blocks a publication, not even in a strict template.
     */
    @Test
    void sectionsInTheWrongOrderDoNotBlockThePublication() {
        givenTemplate(templateWith("""
                {
                  "strict": true,
                  "sections": [
                    { "id": "overview", "heading": "Overview", "required": true },
                    { "id": "api-surface", "heading": "API surface", "required": true }
                  ]
                }
                """));
        givenVersionWithBody(VersionStatus.APPROVED, 3, """
                # API surface

                text

                # Overview

                text
                """);

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
     * A strict template whose <em>optional</em> section is missing does
     * not block the publication either — only the required sections do.
     */
    @Test
    void aMissingOptionalSectionDoesNotBlockThePublication() {
        givenTemplate(templateWith("""
                {
                  "strict": true,
                  "sections": [
                    { "id": "overview", "heading": "Overview", "required": true },
                    { "id": "notes", "heading": "Notes", "required": false }
                  ]
                }
                """));
        givenVersionWithBody(VersionStatus.APPROVED, 3, "# Overview\n\ntext\n");

        service.publishVersion(actorId, documentId, 3);

        org.mockito.Mockito.verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq(actorId),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("document.version.published_noncompliant"),
                org.mockito.ArgumentMatchers.eq("document_version"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq(documentId),
                org.mockito.ArgumentMatchers.anyMap());
    }
}
