package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoleResponse(
        UUID id,
        String code,
        String name,
        String description,
        boolean system,
        int displayOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}