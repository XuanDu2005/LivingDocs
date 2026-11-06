package com.livingdocs.modules.audit;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthenticatedUser;
import com.livingdocs.modules.audit.controller.AuditLogController;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-document audit trail endpoint.
 *
 * <p>The workspace-wide trail cannot answer "what happened to this
 * document": it mixes in every other action of the workspace, and its
 * rows carry no link to the document. These tests pin the rules that
 * make the per-document trail usable — it queries by document, it
 * refuses a document of another workspace, and it resolves the actor's
 * name so a reader knows <em>who</em> acted.
 */
class AuditLogControllerDocumentTrailTest {

    private AuditLogRepository repository;
    private DocumentRepository documentRepository;
    private UserRepository userRepository;
    private WorkspaceService workspaceService;
    private AuditLogController controller;

    private UUID workspaceId;
    private UUID documentId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        documentRepository = mock(DocumentRepository.class);
        userRepository = mock(UserRepository.class);
        workspaceService = mock(WorkspaceService.class);

        controller = new AuditLogController(
                repository, workspaceService, documentRepository, userRepository);

        workspaceId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        // The controller reads the caller from the security context.
        AuthenticatedUser principal = new AuthenticatedUser(
                actorId, "manager@example.com", "hash", true,
                List.of("MANAGER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));

        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getWorkspaceId()).thenReturn(workspaceId);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The trail is read from the document's own index — not from the
     * workspace-wide list that would have to be filtered by hand.
     */
    @Test
    void theTrailIsReadByDocumentId() {
        when(repository.findAllByDocumentId(eq(documentId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        controller.documentTrail(workspaceId, documentId, 0, 50);

        verify(repository).findAllByDocumentId(eq(documentId), any(Pageable.class));
    }

    /**
     * The response carries the document id and the paging envelope, so
     * the reader can walk back to the very first entry of the trail.
     */
    @Test
    void theResponseCarriesTheDocumentIdAndThePagingEnvelope() {
        when(repository.findAllByDocumentId(eq(documentId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        var response = controller.documentTrail(workspaceId, documentId, 0, 50);

        assertEquals(documentId, response.documentId());
        assertEquals(0, response.page());
        assertEquals(0, response.total());
        assertEquals(0, response.totalPages());
    }

    /**
     * A document of another workspace must not be readable through the
     * wrong URL — otherwise a member of two workspaces could read the
     * trail of a document of the other one.
     */
    @Test
    void aDocumentOfAnotherWorkspaceIsNotFound() {
        Document foreign = mock(Document.class);
        when(foreign.getWorkspaceId()).thenReturn(UUID.randomUUID());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(foreign));

        assertThrows(NotFoundException.class,
                () -> controller.documentTrail(workspaceId, documentId, 0, 50));
    }

    /**
     * A document that does not exist is reported as not found rather
     * than as an empty trail.
     */
    @Test
    void anUnknownDocumentIsNotFound() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> controller.documentTrail(workspaceId, documentId, 0, 50));
    }

    /**
     * The actor's display name is resolved, because a reader of the
     * trail has to know who acted, not just a UUID.
     */
    @Test
    void theActorsNameIsResolved() {
        AuditLog entry = mock(AuditLog.class);
        when(entry.getId()).thenReturn(UUID.randomUUID());
        when(entry.getActorUserId()).thenReturn(actorId);
        when(entry.getDocumentId()).thenReturn(documentId);
        when(entry.getAction()).thenReturn("document.version.published");
        when(entry.getCreatedAt()).thenReturn(java.time.OffsetDateTime.now());

        when(repository.findAllByDocumentId(eq(documentId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 50), 1));

        User actor = mock(User.class);
        when(actor.getId()).thenReturn(actorId);
        when(actor.getDisplayName()).thenReturn("Nguyễn Văn A");
        when(actor.getEmail()).thenReturn("a@example.com");
        when(userRepository.findAllById(any())).thenReturn(List.of(actor));

        var response = controller.documentTrail(workspaceId, documentId, 0, 50);

        assertEquals(1, response.items().size());
        assertEquals("Nguyễn Văn A", response.items().get(0).actorName());
    }
}
