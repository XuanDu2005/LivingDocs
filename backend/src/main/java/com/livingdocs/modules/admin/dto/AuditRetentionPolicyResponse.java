package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditRetentionPolicyResponse(
        UUID id,
        String entityType,
        String description,
        int retentionDays,
        String pruneStrategy,
        boolean enabled,
        OffsetDateTime lastPrunedAt,
        OffsetDateTime updatedAt
) {}