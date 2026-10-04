package com.livingdocs.modules.document.dto;

import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API projection of a {@link Document}.
 */
public record DocumentResponse(
        UUID id,
        UUID workspaceId,
        UUID repositoryId,
        UUID templateId,
        UUID headVersionId,
        String title,
        String slug,
        String docType,
        String summary,
        DocumentStatus status,
        boolean autoUpdateEnabled,
        UUID ownerId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt
) {
    public static DocumentResponse from(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getWorkspaceId(),
                d.getRepositoryId(),
                d.getTemplateId(),
                d.getHeadVersionId(),
                d.getTitle(),
                d.getSlug(),
                d.getDocType(),
                d.getSummary(),
                d.getStatus(),
                d.isAutoUpdateEnabled(),
                d.getOwnerId(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getPublishedAt()
        );
    }
}