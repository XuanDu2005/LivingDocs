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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The summary of a document, and what an update may do to it.
 *
 * <p>The endpoint is a full metadata update, but not every field of it
 * is meant to be rewritten on every call. The summary used to be
 * written unconditionally, which meant a caller that only wanted to
 * change the title silently erased the summary — the field was
 * indistinguishable from the ones that were never sent at all.
 */
class DocumentSummaryUpdateTest {

    private DocumentRepository documentRepository;
    private WorkspaceService workspaceService;
    private AuditLogService auditLogService;
    private DocumentService service;

    private UUID actorId;
    private UUID documentId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        workspaceService = mock(WorkspaceService.class);
        auditLogService = mock(AuditLogService.class);
        PermissionService permissionService = mock(PermissionService.class);

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

        actorId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
    }

    /** A document owned by the actor, so they may edit it. */
    private Document givenDocumentWithSummary(String summary) {
        Document document = new Document(
                workspaceId, null, null, "Title", "title", "MODULE_GUIDE",
                summary, false, actorId);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        return document;
    }

    private UpdateDocumentRequest request(String summary) {
        return new UpdateDocumentRequest("New title", summary, null, null, null, false);
    }

    @Test
    void anAbsentSummaryLeavesTheCurrentOneAlone() {
        Document document = givenDocumentWithSummary("The original summary");

        service.update(actorId, workspaceId, documentId, request(null));

        assertEquals("The original summary", document.getSummary());
    }

    @Test
    void aNewSummaryIsWritten() {
        Document document = givenDocumentWithSummary("The original summary");

        service.update(actorId, workspaceId, documentId, request("A new summary"));

        assertEquals("A new summary", document.getSummary());
    }

    @Test
    void anEmptySummaryClearsTheField() {
        Document document = givenDocumentWithSummary("The original summary");

        service.update(actorId, workspaceId, documentId, request(""));

        assertNull(document.getSummary());
    }

    @Test
    void anUnchangedSummaryIsNotRecordedInTheTrail() {
        givenDocumentWithSummary("The original summary");

        service.update(actorId, workspaceId, documentId, request("The original summary"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(any(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), payload.capture());

        // The title changed, the summary did not — so the trail names
        // only the title.
        assertEquals(true, payload.getValue().get("titleChanged"));
        assertEquals(null, payload.getValue().get("summaryChanged"));
    }

    @Test
    void aClearedSummaryIsRecordedInTheTrail() {
        givenDocumentWithSummary("The original summary");

        service.update(actorId, workspaceId, documentId, request(""));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(any(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), payload.capture());

        assertEquals(true, payload.getValue().get("summaryChanged"));
    }

    @Test
    void aBlankSummaryIsStoredAsNull() {
        // A blank string is a summary of spaces. It is normalised to
        // null so the column does not carry a value that reads as an
        // empty summary everywhere else.
        Document document = givenDocumentWithSummary("The original summary");

        service.update(actorId, workspaceId, documentId, request("   "));

        assertNull(document.getSummary());
    }
}
