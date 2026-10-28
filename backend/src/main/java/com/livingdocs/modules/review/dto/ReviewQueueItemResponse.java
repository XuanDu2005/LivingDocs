package com.livingdocs.modules.review.dto;

import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.VersionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row in a review queue.
 *
 * <p>Queues are scoped to a workspace and describe <em>what</em> needs
 * attention plus <em>which action</em> the current viewer is expected to
 * take. The action code lets the UI render a single generic row component
 * with the right buttons attached.
 */
public record ReviewQueueItemResponse(
        UUID versionId,
        UUID documentId,
        UUID workspaceId,
        String documentTitle,
        String documentSlug,
        String docType,
        Integer versionNumber,
        VersionStatus versionStatus,
        Float confidenceScore,
        String actorRole,
        String changeSummary,
        String action,
        OffsetDateTime createdAt
) {
    public static ReviewQueueItemResponse from(UUID versionId, UUID documentId, UUID workspaceId,
                                               String documentTitle, String documentSlug, String docType,
                                               Integer versionNumber, VersionStatus versionStatus,
                                               Float confidenceScore, ActorRole actorRole,
                                               String changeSummary, String action,
                                               OffsetDateTime createdAt) {
        return new ReviewQueueItemResponse(
                versionId, documentId, workspaceId, documentTitle, documentSlug, docType,
                versionNumber, versionStatus, confidenceScore,
                actorRole != null ? actorRole.name() : null,
                changeSummary, action, createdAt);
    }
}
