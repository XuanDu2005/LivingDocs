package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the role-permission matrix: a permission as exposed to the
 * admin UI, plus a flag indicating whether the requested role currently
 * holds it. The {@code granted} flag lets the matrix render a checkbox
 * without the client having to diff two collections.
 */
public record RolePermissionResponse(
        UUID id,
        String code,
        String name,
        String description,
        String category,
        int displayOrder,
        boolean granted,
        OffsetDateTime grantedAt
) {
}
