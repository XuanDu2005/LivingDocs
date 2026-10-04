package com.livingdocs.modules.workspace.dto;

import com.livingdocs.modules.workspace.model.Workspace;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String name,
        String slug,
        String description,
        UUID ownerId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static WorkspaceResponse from(Workspace w) {
        return new WorkspaceResponse(
                w.getId(),
                w.getName(),
                w.getSlug(),
                w.getDescription(),
                w.getOwnerId(),
                w.getCreatedAt(),
                w.getUpdatedAt()
        );
    }
}