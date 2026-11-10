package com.livingdocs.modules.version;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.version.dto.BulkRollbackRequest;
import com.livingdocs.modules.version.dto.BulkRollbackResponse;
import com.livingdocs.modules.version.dto.DocumentRollbackResult;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.version.service.BulkRollbackService;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of a bulk rollback.
 *
 * <p>A bulk rollback is the "undo a bad automatic update across a
 * collection" operation. Two properties matter most: it must never touch
 * a document outside the workspace, and it must not fail as a whole
 * because one document of the collection cannot be rolled back.
 */
class BulkRollbackServiceTest {

    private DocumentRepository documentRepository;
    private DocumentVersionRepository versionRepository;
    private DocumentVersionService versionService;
    private WorkspaceService workspaceService;
    private PermissionService permissionService;
    private AuditLogService auditLogService;
    private BulkRollbackService service;

    private UUID actorId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        versionRepository = mock(DocumentVersionRepository.class);
        versionService = mock(DocumentVersionService.class);
        workspaceService = mock(WorkspaceService.class);
        permissionService = mock(PermissionService.class);
        auditLogService = mock(AuditLogService.class);

        service = new BulkRollbackService(documentRepository, versionRepository,
                versionService, workspaceService, permissionService,
                auditLogService, mock(AuthorizationService.class));

        actorId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
    }

    /**
     * A document of the workspace.
     */
    private Document givenDocument(String title) {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getWorkspaceId()).thenReturn(workspaceId);
        when(document.getTitle()).thenReturn(title);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        return document;
    }

    /**
     * A version that can be restored.
     */
    private DocumentVersion givenVersion(VersionStatus status, Integer number) {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getVersionNumber()).thenReturn(number);
        when(version.getStatus()).thenReturn(status);
        when(version.getBodyMarkdown()).thenReturn("# Restored\n");
        return version;
    }

    /**
     * The version a rollback created.
     *
     * <p>The mock is created before it is handed to a stubbing call:
     * creating it inside {@code when(...)} would leave the previous
     * stubbing unfinished and blow up.
     */
    private DocumentVersion givenCreatedVersion(Integer number) {
        DocumentVersion created = mock(DocumentVersion.class);
        when(created.getId()).thenReturn(UUID.randomUUID());
        when(created.getVersionNumber()).thenReturn(number);
        when(created.getStatus()).thenReturn(VersionStatus.DRAFT);
        return created;
    }

    /**
     * The bulk rollback needs the rollback capability, exactly like the
     * single-document rollback.
     */
    @Test
    void aBulkRollbackNeedsTheRollbackCapability() {
        org.mockito.Mockito.doThrow(new com.livingdocs.common.exception.ForbiddenException("nope"))
                .when(permissionService).requireAnyPermission(actorId, "DOCUMENT_ROLLBACK");

        assertThrows(com.livingdocs.common.exception.ForbiddenException.class,
                () -> service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                        List.of(), null, null,
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null)));

        verify(versionService, never()).rollbackToVersion(any(), any(), any(), any());
    }

    /**
     * The happy path: every document of the collection is restored to
     * the version that was published before its head.
     */
    @Test
    void everyDocumentOfTheCollectionIsRolledBack() {
        Document first = givenDocument("First");
        Document second = givenDocument("Second");

        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, 3);
        // The created versions are built before the stubbing calls: a
        // mock created inside a `thenReturn(...)` argument would leave
        // the outer stubbing unfinished.
        DocumentVersion firstCreated = givenCreatedVersion(7);
        DocumentVersion secondCreated = givenCreatedVersion(4);
        when(versionService.findPreviousPublishedVersion(first)).thenReturn(target);
        when(versionService.findPreviousPublishedVersion(second)).thenReturn(target);
        when(versionService.rollbackToVersion(eq(actorId), eq(first), eq(target), any()))
                .thenReturn(firstCreated);
        when(versionService.rollbackToVersion(eq(actorId), eq(second), eq(target), any()))
                .thenReturn(secondCreated);

        BulkRollbackResponse response = service.bulkRollback(actorId, workspaceId,
                new BulkRollbackRequest(List.of(first.getId(), second.getId()), null, null,
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, "bad auto-update"));

        assertEquals(2, response.total());
        assertEquals(2, response.rolledBack());
        assertEquals(0, response.skipped());
        assertTrue(response.results().stream().allMatch(DocumentRollbackResult::rolledBack));
    }

    /**
     * The reason of the batch ends up in the change summary of every
     * version it creates — otherwise a reader of one document's timeline
     * cannot tell a mass correction from an unrelated edit.
     */
    @Test
    void theReasonOfTheBatchIsRecordedInTheChangeSummary() {
        Document document = givenDocument("Doc");
        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, 2);
        DocumentVersion created = givenCreatedVersion(5);
        when(versionService.findPreviousPublishedVersion(document)).thenReturn(target);
        when(versionService.rollbackToVersion(eq(actorId), eq(document), eq(target), any()))
                .thenReturn(created);

        service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                List.of(document.getId()), null, null,
                BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, "the auto-update broke the API docs"));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(versionService).rollbackToVersion(eq(actorId), eq(document), eq(target), summary.capture());
        assertTrue(summary.getValue().contains("the auto-update broke the API docs"),
                "the summary carries the reason: " + summary.getValue());
    }

    /**
     * One document that cannot be rolled back must not stop the others:
     * the batch is partially successful by design.
     */
    @Test
    void aDocumentThatCannotBeRolledBackDoesNotStopTheBatch() {
        Document good = givenDocument("Good");
        Document bad = givenDocument("Bad");

        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, 3);
        DocumentVersion created = givenCreatedVersion(9);
        when(versionService.findPreviousPublishedVersion(good)).thenReturn(target);
        when(versionService.findPreviousPublishedVersion(bad)).thenReturn(null);
        when(versionService.rollbackToVersion(eq(actorId), eq(good), eq(target), any()))
                .thenReturn(created);

        BulkRollbackResponse response = service.bulkRollback(actorId, workspaceId,
                new BulkRollbackRequest(List.of(good.getId(), bad.getId()), null, null,
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null));

        assertEquals(2, response.total());
        assertEquals(1, response.rolledBack());
        assertEquals(1, response.skipped());
        DocumentRollbackResult badResult = response.results().stream()
                .filter(r -> r.documentId().equals(bad.getId()))
                .findFirst()
                .orElseThrow();
        assertFalse(badResult.rolledBack());
        assertEquals("No version to roll back to", badResult.reason());
    }

    /**
     * A document of another workspace is never touched, even when it is
     * named explicitly in the selection.
     */
    @Test
    void aDocumentOfAnotherWorkspaceIsNeverTouched() {
        Document foreign = mock(Document.class);
        when(foreign.getId()).thenReturn(UUID.randomUUID());
        when(foreign.getWorkspaceId()).thenReturn(UUID.randomUUID());
        when(foreign.getTitle()).thenReturn("Foreign");
        when(documentRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        BulkRollbackResponse response = service.bulkRollback(actorId, workspaceId,
                new BulkRollbackRequest(List.of(foreign.getId()), null, null,
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null));

        assertEquals(0, response.rolledBack());
        assertEquals(1, response.skipped());
        verify(versionService, never()).rollbackToVersion(any(), any(), any(), any());
    }

    /**
     * The {@code VERSION_NUMBER} strategy restores the same version
     * number of every document — the "a bad batch was published" case.
     */
    @Test
    void theVersionNumberStrategyRestoresTheSameVersionEverywhere() {
        Document document = givenDocument("Doc");
        DocumentVersion target = givenVersion(VersionStatus.PUBLISHED, 5);
        DocumentVersion created = givenCreatedVersion(9);
        when(versionRepository.findByDocumentIdAndVersionNumber(document.getId(), 5))
                .thenReturn(Optional.of(target));
        when(versionService.rollbackToVersion(eq(actorId), eq(document), eq(target), any()))
                .thenReturn(created);

        service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                List.of(document.getId()), null, null,
                BulkRollbackRequest.Strategy.VERSION_NUMBER, 5, null));

        verify(versionService).rollbackToVersion(eq(actorId), eq(document), eq(target), any());
    }

    /**
     * The {@code VERSION_NUMBER} strategy needs the version number — a
     * request without it is a mistake, not a default.
     */
    @Test
    void theVersionNumberStrategyNeedsAVersionNumber() {
        Document document = givenDocument("Doc");

        assertThrows(BadRequestException.class,
                () -> service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                        List.of(document.getId()), null, null,
                        BulkRollbackRequest.Strategy.VERSION_NUMBER, null, null)));
    }

    /**
     * The batch itself is recorded in the audit trail, so a reader of the
     * workspace trail can see that the corrections were one deliberate
     * action.
     */
    @Test
    void theBatchIsRecordedInTheAuditTrail() {
        Document document = givenDocument("Doc");
        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, 2);
        DocumentVersion created = givenCreatedVersion(4);
        when(versionService.findPreviousPublishedVersion(document)).thenReturn(target);
        when(versionService.rollbackToVersion(eq(actorId), eq(document), eq(target), any()))
                .thenReturn(created);

        service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                List.of(document.getId()), null, null,
                BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, "cleanup"));

        verify(auditLogService).record(eq(actorId), anyString(),
                eq("document.version.bulk_rolled_back"),
                eq("document"), eq(""), eq(workspaceId),
                isNull(), any());
    }

    /**
     * An empty collection is refused: there is nothing to roll back, and
     * a silent success would be misleading.
     */
    @Test
    void anEmptyCollectionIsRefused() {
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of());

        assertThrows(BadRequestException.class,
                () -> service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                        List.of(), null, null,
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null)));
    }

    /**
     * The batch is capped: a rollback writes a version per document, so
     * an unbounded batch would write an unbounded number of rows.
     */
    @Test
    void aBatchIsCapped() {
        List<Document> many = new java.util.ArrayList<>();
        for (int i = 0; i <= BulkRollbackService.MAX_DOCUMENTS_PER_BATCH; i++) {
            Document document = mock(Document.class);
            when(document.getId()).thenReturn(UUID.randomUUID());
            when(document.getWorkspaceId()).thenReturn(workspaceId);
            many.add(document);
        }
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(many);

        assertThrows(BadRequestException.class,
                () -> service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                        List.of(), null, null,
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null)));
    }

    /**
     * The repository axis of the selection resolves to the documents of
     * the repository — scoped to the workspace.
     */
    @Test
    void theRepositoryAxisResolvesTheCollection() {
        Document document = givenDocument("Doc");
        UUID repositoryId = UUID.randomUUID();
        when(documentRepository.findAllByWorkspaceIdAndRepositoryId(workspaceId, repositoryId))
                .thenReturn(List.of(document));
        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, 2);
        DocumentVersion created = givenCreatedVersion(6);
        when(versionService.findPreviousPublishedVersion(document)).thenReturn(target);
        when(versionService.rollbackToVersion(eq(actorId), eq(document), eq(target), any()))
                .thenReturn(created);

        BulkRollbackResponse response = service.bulkRollback(actorId, workspaceId,
                new BulkRollbackRequest(List.of(), repositoryId, null,
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null));

        assertEquals(1, response.rolledBack());
    }

    /**
     * The doc-type axis of the selection resolves to the documents of the
     * type — scoped to the workspace.
     */
    @Test
    void theDocTypeAxisResolvesTheCollection() {
        Document document = givenDocument("Doc");
        when(documentRepository.findAllByWorkspaceIdAndDocTypeOrderByUpdatedAtDesc(
                workspaceId, "API_REFERENCE")).thenReturn(List.of(document));
        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, 2);
        DocumentVersion created = givenCreatedVersion(6);
        when(versionService.findPreviousPublishedVersion(document)).thenReturn(target);
        when(versionService.rollbackToVersion(eq(actorId), eq(document), eq(target), any()))
                .thenReturn(created);

        BulkRollbackResponse response = service.bulkRollback(actorId, workspaceId,
                new BulkRollbackRequest(List.of(), null, "API_REFERENCE",
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null));

        assertEquals(1, response.rolledBack());
    }

    /**
     * The actor has to be a member of the workspace — a rollback is an
     * editorial action on the workspace's own content.
     */
    @Test
    void theActorHasToBeAMemberOfTheWorkspace() {
        Document document = givenDocument("Doc");
        when(documentRepository.findAllByWorkspaceIdAndDocTypeOrderByUpdatedAtDesc(
                workspaceId, "API_REFERENCE")).thenReturn(List.of(document));

        org.mockito.Mockito.doThrow(new com.livingdocs.common.exception.ForbiddenException("nope"))
                .when(workspaceService).requireMember(actorId, workspaceId);

        assertThrows(com.livingdocs.common.exception.ForbiddenException.class,
                () -> service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                        List.of(), null, "API_REFERENCE",
                        BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null)));
    }

    /**
     * The set of roles the bulk rollback relies on is the same one the
     * single-document rollback uses.
     */
    @Test
    void theBulkRollbackUsesTheRollbackCapability() {
        Document document = givenDocument("Doc");
        when(documentRepository.findAllByWorkspaceIdAndDocTypeOrderByUpdatedAtDesc(
                workspaceId, "API_REFERENCE")).thenReturn(List.of(document));
        DocumentVersion target = givenVersion(VersionStatus.SUPERSEDED, 2);
        DocumentVersion created = givenCreatedVersion(6);
        when(versionService.findPreviousPublishedVersion(document)).thenReturn(target);
        when(versionService.rollbackToVersion(eq(actorId), eq(document), eq(target), any()))
                .thenReturn(created);

        service.bulkRollback(actorId, workspaceId, new BulkRollbackRequest(
                List.of(), null, "API_REFERENCE",
                BulkRollbackRequest.Strategy.PREVIOUS_PUBLISHED, null, null));

        verify(permissionService).requireAnyPermission(actorId, "DOCUMENT_ROLLBACK");
    }
}
