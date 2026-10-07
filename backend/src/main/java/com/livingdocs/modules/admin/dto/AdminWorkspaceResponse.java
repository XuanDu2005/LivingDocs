package com.livingdocs.modules.admin.dto;

import com.livingdocs.modules.workspace.model.Workspace;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Administrator-facing projection of a {@link Workspace} enriched with
 * counts that are too expensive to compute on every regular API call.
 */
public record AdminWorkspaceResponse(
        UUID id,
        String name,
        String slug,
        String description,
        UUID ownerId,
        long memberCount,
        long managerCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AdminWorkspaceResponse from(Workspace w, long memberCount, long managerCount) {
        return new AdminWorkspaceResponse(
                w.getId(),
                w.getName(),
                w.getSlug(),
                w.getDescription(),
                w.getOwnerId(),
                memberCount,
                managerCount,
                w.getCreatedAt(),
                w.getUpdatedAt()
        );
    }
}