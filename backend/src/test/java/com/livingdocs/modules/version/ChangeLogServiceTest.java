package com.livingdocs.modules.version;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.version.dto.ChangeLogFilter;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.version.service.ChangeLogService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The workspace-level change log.
 *
 * <p>The requirement asks for a change log across the repository of a
 * workspace, filterable by role, document, repository and date range.
 * These tests pin the rules that make the log trustworthy: the filter
 * reaches the database instead of being applied in memory, the actor's
 * role is compared as an enum, and a document of another workspace
 * cannot be read through the wrong URL.
 */
class ChangeLogServiceTest {

    private DocumentVersionRepository versionRepository;
    private DocumentRepository documentRepository;
    private UserRepository userRepository;
    private WorkspaceService workspaceService;
    private ChangeLogService service;

    private UUID workspaceId;
    private UUID actorId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        versionRepository = mock(DocumentVersionRepository.class);
        documentRepository = mock(DocumentRepository.class);
        userRepository = mock(UserRepository.class);
        workspaceService = mock(WorkspaceService.class);

        service = new ChangeLogService(
                versionRepository, documentRepository, userRepository, workspaceService);

        workspaceId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        when(documentRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    private ChangeLogFilter filter(String actorRole) {
        return new ChangeLogFilter(null, null, actorRole, null, null, null, null);
    }

    /**
     * The role filter has to reach the query as an enum: the column is
     * mapped with {@code EnumType.STRING}, so a raw string would not
     * bind to it.
     */
    @Test
    void theRoleFilterIsPassedToTheQueryAsAnEnum() {
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        service.list(actorId, workspaceId, filter("AI"), 0, 50);

        ArgumentCaptor<ActorRole> role = ArgumentCaptor.forClass(ActorRole.class);
        verify(versionRepository).findChangeLog(
                eq(workspaceId), anyBoolean(), any(), anyBoolean(), any(),
                eq(true), role.capture(), anyBoolean(), any(), anyBoolean(), any(), any());
        assertEquals(ActorRole.AI, role.getValue());
    }

    /**
     * The role filter is case-insensitive — the UI sends the upper-case
     * name, but a hand-written request may not.
     */
    @Test
    void theRoleFilterIsCaseInsensitive() {
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        service.list(actorId, workspaceId, filter("ai"), 0, 50);

        ArgumentCaptor<ActorRole> role = ArgumentCaptor.forClass(ActorRole.class);
        verify(versionRepository).findChangeLog(
                eq(workspaceId), anyBoolean(), any(), anyBoolean(), any(),
                eq(true), role.capture(), anyBoolean(), any(), anyBoolean(), any(), any());
        assertEquals(ActorRole.AI, role.getValue());
    }

    /**
     * An unknown role must not break the page: it is treated as "no
     * role filter" rather than as an error.
     */
    @Test
    void anUnknownRoleMeansNoRoleFilter() {
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        service.list(actorId, workspaceId, filter("WIZARD"), 0, 50);

        verify(versionRepository).findChangeLog(
                eq(workspaceId), anyBoolean(), any(), anyBoolean(), any(),
                eq(false), any(), anyBoolean(), any(), anyBoolean(), any(), any());
    }

    /**
     * The date range is passed through as-is, so the backend filters on
     * the creation time of the version.
     */
    @Test
    void theDateRangeIsPassedToTheQuery() {
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        OffsetDateTime from = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        ChangeLogFilter range = new ChangeLogFilter(null, null, null, from, to, null, null);

        service.list(actorId, workspaceId, range, 0, 50);

        verify(versionRepository).findChangeLog(
                eq(workspaceId), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), eq(true), eq(from), eq(true), eq(to), any());
    }

    /**
     * The default ordering is newest change first.
     */
    @Test
    void theDefaultOrderingIsNewestFirst() {
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        service.list(actorId, workspaceId, filter(null), 0, 50);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(versionRepository).findChangeLog(
                eq(workspaceId), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                pageable.capture());
        Sort sort = pageable.getValue().getSort();
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("createdAt").getDirection());
    }

    /**
     * The log can be ordered by the version number, which is what a
     * reader wants when comparing two versions of a document.
     */
    @Test
    void theLogCanBeSortedByVersionNumber() {
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        ChangeLogFilter sorted = new ChangeLogFilter(
                null, null, null, null, null, "VERSION_NUMBER", "ASC");

        service.list(actorId, workspaceId, sorted, 0, 50);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(versionRepository).findChangeLog(
                eq(workspaceId), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                pageable.capture());
        Sort sort = pageable.getValue().getSort();
        assertEquals(Sort.Direction.ASC, sort.getOrderFor("versionNumber").getDirection());
    }

    /**
     * A page is never unbounded: a single request must not be able to
     * pull the whole version history of a workspace into memory.
     */
    @Test
    void thePageSizeIsCapped() {
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        service.list(actorId, workspaceId, filter(null), 0, 100_000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(versionRepository).findChangeLog(
                eq(workspaceId), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                pageable.capture());
        assertEquals(ChangeLogService.MAX_PAGE_SIZE, pageable.getValue().getPageSize());
    }

    /**
     * The document of the filter has to belong to the workspace of the
     * path — otherwise a member of two workspaces could read the
     * history of a document of the other one through the wrong URL.
     */
    @Test
    void aDocumentOfAnotherWorkspaceIsNotFound() {
        Document foreign = mock(Document.class);
        when(foreign.getWorkspaceId()).thenReturn(UUID.randomUUID());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(foreign));

        ChangeLogFilter byDocument = new ChangeLogFilter(
                documentId, null, null, null, null, null, null);

        assertThrows(NotFoundException.class,
                () -> service.list(actorId, workspaceId, byDocument, 0, 50));
        verify(versionRepository, never()).findChangeLog(
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), any());
    }

    /**
     * A document that does not exist is reported as not found rather
     * than as an empty log.
     */
    @Test
    void anUnknownDocumentIsNotFound() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        ChangeLogFilter byDocument = new ChangeLogFilter(
                documentId, null, null, null, null, null, null);

        assertThrows(NotFoundException.class,
                () -> service.list(actorId, workspaceId, byDocument, 0, 50));
    }

    /**
     * The rows of the log name their document and their actor: a change
     * log that only carries ids forces the reader to open every
     * document to find out what they are looking at.
     */
    @Test
    void theRowsCarryTheDocumentAndTheActorName() {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getDocumentId()).thenReturn(documentId);
        when(version.getVersionNumber()).thenReturn(7);
        when(version.getActorRole()).thenReturn(ActorRole.AI);
        when(version.getStatus()).thenReturn(VersionStatus.PUBLISHED);
        when(version.getCreatedAt()).thenReturn(OffsetDateTime.now());

        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getTitle()).thenReturn("API reference");
        when(document.getSlug()).thenReturn("api-reference");
        when(document.getRepositoryId()).thenReturn(UUID.randomUUID());

        when(documentRepository.findAllById(any())).thenReturn(List.of(document));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(version), PageRequest.of(0, 50), 1));

        var page = service.list(actorId, workspaceId, filter(null), 0, 50);

        assertEquals(1, page.getTotalElements());
        var entry = page.getContent().get(0);
        assertEquals("API reference", entry.documentTitle());
        assertEquals("api-reference", entry.documentSlug());
        assertEquals(7, entry.versionNumber());
        assertEquals(ActorRole.AI, entry.actorRole());
    }

    /**
     * The display name of the actor is resolved, because a reader of
     * the log has to know who acted, not just a UUID.
     */
    @Test
    void theActorsNameIsResolved() {
        UUID versionActorId = UUID.randomUUID();

        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getDocumentId()).thenReturn(documentId);
        when(version.getVersionNumber()).thenReturn(1);
        when(version.getActorRole()).thenReturn(ActorRole.STAFF);
        when(version.getActorUserId()).thenReturn(versionActorId);
        when(version.getCreatedAt()).thenReturn(OffsetDateTime.now());

        User actor = mock(User.class);
        when(actor.getId()).thenReturn(versionActorId);
        when(actor.getDisplayName()).thenReturn("Nguyễn Văn A");
        when(actor.getEmail()).thenReturn("a@example.com");
        when(userRepository.findAllById(any())).thenReturn(List.of(actor));
        when(versionRepository.findChangeLog(any(), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(version), PageRequest.of(0, 50), 1));

        var page = service.list(actorId, workspaceId, filter(null), 0, 50);

        assertEquals("Nguyễn Văn A", page.getContent().get(0).actorName());
    }
}
