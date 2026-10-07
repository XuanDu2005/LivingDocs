package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateRetentionPolicyRequest(
        @NotBlank(message = "entityType is required")
        String entityType,

        String existingEntityType, // when renaming

        @NotNull @Min(0)
        Integer retentionDays,

        String description,

        Boolean enabled,

        String pruneStrategy // HARD_DELETE | ARCHIVE | ANONYMIZE
) {}