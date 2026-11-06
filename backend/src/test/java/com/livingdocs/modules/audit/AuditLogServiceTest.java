package com.livingdocs.modules.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The linking of an audit entry to the document it concerns.
 *
 * <p>This is what makes "the complete audit trail of a document"
 * answerable as a query instead of a JSON lookup. The trail was
 * previously the workspace-wide list, in which the association with the
 * document existed only inside the payload — so a reader had to page
 * through the whole workspace and filter by hand.
 */
class AuditLogServiceTest {

    private AuditLogRepository repository;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        when(repository.save(any(AuditLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new AuditLogService(repository, new ObjectMapper());
    }

    private AuditLog captureRecorded() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * An explicit document id wins over anything the payload says.
     */
    @Test
    void anExplicitDocumentIdIsRecorded() {
        UUID documentId = UUID.randomUUID();

        service.record(UUID.randomUUID(), "MANAGER", "document.version.published",
                "document_version", UUID.randomUUID().toString(),
                UUID.randomUUID(), documentId,
                Map.of("documentId", documentId.toString(), "versionNumber", 3));

        assertEquals(documentId, captureRecorded().getDocumentId());
    }

    /**
     * When the caller does not name the document, the id is taken from
     * the payload — every document-scoped action already carried it
     * there.
     */
    @Test
    void theDocumentIdIsDerivedFromThePayload() {
        UUID documentId = UUID.randomUUID();

        service.record(UUID.randomUUID(), "STAFF", "document.review",
                "document_version", UUID.randomUUID().toString(),
                UUID.randomUUID(),
                Map.of("documentId", documentId.toString(), "decision", "APPROVED"));

        assertEquals(documentId, captureRecorded().getDocumentId());
    }

    /**
     * A workspace-level action belongs to no document.
     */
    @Test
    void aWorkspaceLevelActionHasNoDocument() {
        service.record(UUID.randomUUID(), "ADMIN", "user.roles.replace",
                "user", UUID.randomUUID().toString(), null,
                Map.of("now", "STAFF"));

        assertNull(captureRecorded().getDocumentId());
    }

    /**
     * A malformed document id must not stop the entry from being
     * recorded: losing the audit entry would be worse than losing the
     * link to the document.
     */
    @Test
    void aMalformedDocumentIdIsIgnored() {
        service.record(UUID.randomUUID(), "STAFF", "document.review",
                "document_version", UUID.randomUUID().toString(),
                UUID.randomUUID(),
                Map.of("documentId", "not-a-uuid"));

        AuditLog entry = captureRecorded();
        assertNotNull(entry);
        assertNull(entry.getDocumentId());
    }

    /**
     * The payload is still written — the link to the document is
     * additional information, not a replacement.
     */
    @Test
    void thePayloadIsPreserved() throws Exception {
        UUID documentId = UUID.randomUUID();

        service.record(UUID.randomUUID(), "AI", "document.version.ai_created",
                "document_version", UUID.randomUUID().toString(),
                UUID.randomUUID(), documentId,
                Map.of("documentId", documentId.toString(), "versionNumber", 7));

        // The key order of a Map is not defined, so the payload is
        // compared as parsed JSON rather than as a string.
        JsonNode payload = new ObjectMapper()
                .readTree(captureRecorded().getPayload());
        assertEquals(documentId.toString(), payload.get("documentId").asText());
        assertEquals(7, payload.get("versionNumber").asInt());
    }
}
