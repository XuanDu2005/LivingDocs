package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UserWithRolesResponse(
        UUID id,
        String email,
        String displayName,
        boolean enabled,
        OffsetDateTime createdAt,
        List<String> roles
) {}