package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Catalogue response for a single permission. Returned alongside the
 * matrix so the admin UI can render the list of available permissions
 * even when looking at a fresh role.
 */
public record PermissionResponse(
        UUID id,
        String code,
        String name,
        String description,
        String category,
        int displayOrder,
        boolean system,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
