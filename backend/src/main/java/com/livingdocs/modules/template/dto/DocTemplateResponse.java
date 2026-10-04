package com.livingdocs.modules.template.dto;

import com.livingdocs.modules.template.model.DocTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API projection of a documentation template.
 */
public record DocTemplateResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String slug,
        String description,
        String docType,
        Integer version,
        String bodyJson,
        boolean isDefault,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static DocTemplateResponse from(DocTemplate t) {
        return new DocTemplateResponse(
                t.getId(),
                t.getWorkspaceId(),
                t.getName(),
                t.getSlug(),
                t.getDescription(),
                t.getDocType(),
                t.getVersion(),
                t.getBody(),
                t.isDefault(),
                t.getCreatedBy(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}