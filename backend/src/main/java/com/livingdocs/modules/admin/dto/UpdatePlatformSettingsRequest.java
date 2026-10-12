package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

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
        float defaultAiConfidenceThreshold,
        // V17: doc-generation defaults
        @NotBlank
        @Pattern(regexp = "MARKDOWN|HTML|PDF",
                message = "must be one of MARKDOWN, HTML, PDF")
        String defaultOutputFormat,
        /** Optional. When null, the platform has no default-template binding. */
        UUID defaultTemplateId,
        @NotBlank
        @Pattern(regexp = "MANUAL|ON_COMMIT|ON_PULL_REQUEST|ON_MERGE|SCHEDULED",
                message = "must be one of MANUAL, ON_COMMIT, ON_PULL_REQUEST, ON_MERGE, SCHEDULED")
        String defaultGenerationTrigger
) {}
