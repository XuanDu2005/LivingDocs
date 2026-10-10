package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Admin payload to update the platform-wide defaults that every
 * newly-created workspace inherits.
 */
public record UpdatePlatformSettingsRequest(
        boolean defaultAutoUpdateOnCommit,
        boolean defaultAutoUpdateOnPr,
        boolean defaultAutoUpdateOnMerge,
        @NotBlank
        @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL",
                message = "must be one of LOW, MEDIUM, HIGH, CRITICAL")
        String defaultDriftSeverityThreshold,
        boolean defaultRequireManagerApproval,
        @NotBlank
        @Pattern(regexp = "WARN|BLOCK",
                message = "must be one of WARN, BLOCK")
        String defaultMergePolicyCritical,
        @DecimalMin("0.0") @DecimalMax("1.0")
        float defaultAiConfidenceThreshold
) {}
