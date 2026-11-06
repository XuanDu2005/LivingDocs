package com.livingdocs.modules.audit.dto;

import com.livingdocs.modules.audit.model.AuditLog;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API projection of one audit entry.
 *
 * <p>The raw {@link AuditLog} entity is not exposed directly here: the
 * payload is a JSON-encoded string, and the actor is a bare UUID. A
 * reader of a document's trail has to be able to render both without
 * parsing anything themselves, so the payload stays a string (the UI
 * expands it lazily) and the actor's display name is resolved by the
 * server.
 */
public record AuditLogResponse(
        UUID id,
        UUID actorUserId,
        String actorName,
        String actorRole,
        String action,
        String resourceType,
        String resourceId,
        UUID workspaceId,
        UUID documentId,
        /** JSON-encoded context of the action; the UI parses it lazily. */
        String payload,
        OffsetDateTime createdAt
) {
    public static AuditLogResponse from(AuditLog entry) {
        return from(entry, null);
    }

    public static AuditLogResponse from(AuditLog entry, String actorName) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getActorUserId(),
                actorName,
                entry.getActorRole(),
                entry.getAction(),
                entry.getResourceType(),
                entry.getResourceId(),
                entry.getWorkspaceId(),
                entry.getDocumentId(),
                entry.getPayload(),
                entry.getCreatedAt()
        );
    }
}
