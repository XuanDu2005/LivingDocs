package com.livingdocs.modules.document;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.dto.UpdateDocumentRequest;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.document.service.DocumentService;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ownership rules of a document.
 *
 * <p>Two of them are tested here: the owner of a document may edit it,
 * and only a workspace manager may hand it to somebody else. The
 * second one is the reason this file exists — the first rule is
 * already exercised by the metadata update endpoint, but nothing
 * stopped the owner from passing the document on and shedding the
 * responsibility that comes with it.
 */
class DocumentOwnershipTest {

    private DocumentRepository documentRepository;
    private WorkspaceService workspaceService;
    private AuditLogService auditLogService;
    private PermissionService permissionService;
    private DocumentService service;

    private UUID managerId;
    private UUID ownerId;
    private UUID newOwnerId;
    private UUID documentId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        workspaceService = mock(WorkspaceService.class);
        auditLogService = mock(AuditLogService.class);
        permissionService = mock(PermissionService.class);

        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.platformRoles(any())).thenReturn(java.util.Set.of());

        service = new DocumentService(
                documentRepository,
                mock(DocumentVersionRepository.class),
                workspaceService,
                mock(DocTemplateService.class),
                auditLogService,
                authorizationService,
                permissionService);

        managerId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        newOwnerId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
    }

    /**
     * A document owned by {@code ownerId}, so that the caller is not
     * automatically a manager.
     */
    private Document givenDocumentOwnedBy(UUID owner) {
        Document document = new Document(
                workspaceId, null, null, "Title", "title", "MODULE_GUIDE",
                null, false, owner);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        return document;
    }

    private UpdateDocumentRequest request(UUID ownerId) {
        return new UpdateDocumentRequest("New title", null, null, null, ownerId, false);
    }

    @Test
    void managerCanReassignTheDocument() {
        Document document = givenDocumentOwnedBy(ownerId);
        when(workspaceService.requireRole(managerId, workspaceId, WorkspaceRole.MANAGER))
                .thenReturn(WorkspaceRole.MANAGER);

        service.update(managerId, workspaceId, documentId, request(newOwnerId));

        assertEquals(newOwnerId, document.getOwnerId());
        verify(workspaceService).requireMember(newOwnerId, workspaceId);
    }

    @Test
    void theAuditTrailNamesBothTheOldAndTheNewOwner() {
        givenDocumentOwnedBy(ownerId);
        when(workspaceService.requireRole(managerId, workspaceId, WorkspaceRole.MANAGER))
                .thenReturn(WorkspaceRole.MANAGER);

        service.update(managerId, workspaceId, documentId, request(newOwnerId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(any(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), payload.capture());

        Map<String, Object> body = payload.getValue();
        assertEquals(true, body.get("ownerIdChanged"));
        assertEquals(ownerId.toString(), body.get("previousOwnerId"));
        assertEquals(newOwnerId.toString(), body.get("ownerId"));
    }

    @Test
    void theOwnerCannotHandTheDocumentToSomebodyElse() {
        Document document = givenDocumentOwnedBy(ownerId);
        // The owner may edit the document — that check passes — but the
        // reassignment check must refuse.
        doThrowForbiddenOnManagerRole(ownerId);

        assertThrows(ForbiddenException.class,
                () -> service.update(ownerId, workspaceId, documentId, request(newOwnerId)));

        // The document keeps its owner.
        assertEquals(ownerId, document.getOwnerId());
    }

    @Test
    void anOwnerIdThatIsNotAWorkspaceMemberIsRefused() {
        givenDocumentOwnedBy(ownerId);
        when(workspaceService.requireRole(managerId, workspaceId, WorkspaceRole.MANAGER))
                .thenReturn(WorkspaceRole.MANAGER);
        doThrowForbiddenOnMember(newOwnerId);

        assertThrows(ForbiddenException.class,
                () -> service.update(managerId, workspaceId, documentId, request(newOwnerId)));
    }

    @Test
    void omittingTheOwnerLeavesTheOwnerAlone() {
        Document document = givenDocumentOwnedBy(ownerId);

        service.update(ownerId, workspaceId, documentId,
                new UpdateDocumentRequest("New title", null, null, null, null, false));

        assertEquals(ownerId, document.getOwnerId());
        // No manager check was needed — nobody tried to reassign.
        verify(workspaceService, never())
                .requireRole(any(), any(), any());
    }

    /**
     * A workspace member who is not a manager fails the MANAGER check.
     */
    private void doThrowForbiddenOnManagerRole(UUID actor) {
        when(workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MANAGER))
                .thenThrow(new ForbiddenException("Insufficient permissions in this workspace"));
    }

    /**
     * Somebody outside the workspace fails the membership check.
     */
    private void doThrowForbiddenOnMember(UUID candidate) {
        org.mockito.Mockito.doThrow(new ForbiddenException("You are not a member of this workspace"))
                .when(workspaceService).requireMember(candidate, workspaceId);
    }
}
