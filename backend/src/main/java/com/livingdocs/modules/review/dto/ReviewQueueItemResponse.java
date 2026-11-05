package com.livingdocs.modules.review.dto;

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
 *
 * <p>Several fields exist purely so the reviewer can <em>prioritise</em>
 * the queue without opening every document:
 * <ul>
 *   <li>{@code repositoryId} / {@code templateId} — what the document is
 *       about, so the queue can be filtered;</li>
 *   <li>{@code maxDriftSeverity} / {@code openDriftCount} — requirement
 *       #6, "verify the content is consistent with the source code": the
 *       worst open drift alert attached to the document;</li>
 *   <li>{@code templateCompliant} — requirement #5, "verify template
 *       structure", so a reviewer sees the gap without opening the
 *       document;</li>
 *   <li>{@code submittedAt} / {@code submittedByName} — when the version
 *       was handed over and by whom.</li>
 * </ul>
 *
 * <p>{@code submittedAt} is deliberately separate from {@code createdAt}:
 * the latter is when the version was <em>written</em> (which for an AI
 * version can be days before anybody looked at it), the former is when
 * it entered the queue. Sorting by {@code createdAt} would put the
 * stale drafts first.
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
        OffsetDateTime createdAt,
        OffsetDateTime submittedAt,
        UUID submittedByUserId,
        String submittedByName,
        UUID repositoryId,
        UUID templateId,
        String templateName,
        String maxDriftSeverity,
        Integer openDriftCount,
        Boolean templateCompliant
) {
}
