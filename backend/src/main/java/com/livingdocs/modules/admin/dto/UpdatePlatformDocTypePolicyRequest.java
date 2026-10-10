package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Admin payload to update a single doc-type policy.
 */
public record UpdatePlatformDocTypePolicyRequest(
        @NotBlank
        @Pattern(regexp = "AUTO_APPLY|MANAGER_REVIEW",
                message = "must be one of AUTO_APPLY, MANAGER_REVIEW")
        String workflow
) {}
