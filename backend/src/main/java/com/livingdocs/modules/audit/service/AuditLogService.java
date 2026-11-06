package com.livingdocs.modules.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit log. All governance-relevant actions flow through
 * {@link #record} so the audit trail is always consistent.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /**
     * The key under which a document-scoped action carries the document
     * id in its payload.
     */
    public static final String PAYLOAD_DOCUMENT_ID = "documentId";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(UUID actorUserId, String actorRole, String action,
                       String resourceType, String resourceId,
                       UUID workspaceId, Map<String, ?> payload) {
        record(actorUserId, actorRole, action, resourceType, resourceId,
                workspaceId, null, payload);
    }

    /**
     * Record one audit entry.
     *
     * <p>The document the entry belongs to is taken from the explicit
     * {@code documentId} argument when there is one, and otherwise
     * derived from the payload's {@code documentId} key. The derivation
     * matters: it is what makes the trail of a document answerable as a
     * query instead of a JSON lookup, and every document-scoped action
     * already carries that key in its payload.
     *
     * @param documentId the document the action concerns; {@code null}
     *                   for workspace-level actions
     */
    @Transactional
    public void record(UUID actorUserId, String actorRole, String action,
                       String resourceType, String resourceId,
                       UUID workspaceId, UUID documentId,
                       Map<String, ?> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit payload, recording as empty", e);
            json = "{}";
        }
        AuditLog entry = new AuditLog(actorUserId, actorRole, action, resourceType,
                resourceId, workspaceId,
                documentId != null ? documentId : documentIdFrom(payload),
                json);
        repository.save(entry);
    }

    /**
     * The document id carried by a payload, or {@code null}.
     *
     * <p>A malformed value is ignored rather than fatal: an audit entry
     * must be recorded even when its payload holds something unexpected.
     * The entry still shows up in the workspace-wide trail; it just does
     * not show up in the trail of a document.
     */
    private UUID documentIdFrom(Map<String, ?> payload) {
        if (payload == null) {
            return null;
        }
        Object raw = payload.get(PAYLOAD_DOCUMENT_ID);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            log.debug("Audit payload carries a malformed documentId: {}", raw);
            return null;
        }
    }
}
