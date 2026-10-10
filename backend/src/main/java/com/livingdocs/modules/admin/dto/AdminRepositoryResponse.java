package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;

/**
 * Cross-tenant view of a single connected repository. Aggregates data
 * from the {@code repositories} table, the
 * {@code drift_alerts} table and the workspace catalogue so the admin
 * browser can render one row per repository without N+1 round-trips.
 */
public record AdminRepositoryResponse(
        // --- repository identity ---
        String id,
        long githubId,
        String owner,
        String name,
        String fullName,
        String defaultBranch,
        String htmlUrl,
        String description,
        boolean isPrivate,
        String status,

        // --- workspace context ---
        String workspaceId,
        String workspaceName,
        String workspaceSlug,

        // --- connector / activity ---
        String connectedBy,
        OffsetDateTime connectedAt,
        OffsetDateTime lastSyncedAt,

        // --- operational aggregates ---
        long documentCount,
        long openDriftCount,
        long totalDriftCount,
        String lastIndexJobStatus,
        OffsetDateTime lastIndexJobAt,

        // --- audit ---
        OffsetDateTime updatedAt
) {
}
