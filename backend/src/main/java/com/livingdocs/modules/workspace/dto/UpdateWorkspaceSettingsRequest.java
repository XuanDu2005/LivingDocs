package com.livingdocs.modules.workspace.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record UpdateWorkspaceSettingsRequest(
    @NotBlank
    String driftSeverityThreshold,

    boolean autoUpdateOnPr,

    boolean autoUpdateOnCommit,

    boolean requireManagerApproval,

    @NotBlank
    String mergePolicyCritical,

    @Min(0)
    @Max(100)
    float aiConfidenceThreshold,

    // V17: per-workspace overrides of platform doc-generation defaults.
    // Pass null to inherit from the platform default.
    @Pattern(regexp = "MARKDOWN|HTML|PDF",
            message = "must be one of MARKDOWN, HTML, PDF")
    String defaultOutputFormat,

    /** Null = inherit platform default. */
    UUID defaultTemplateId,

    @Pattern(regexp = "MANUAL|ON_COMMIT|ON_PULL_REQUEST|ON_MERGE|SCHEDULED",
            message = "must be one of MANUAL, ON_COMMIT, ON_PULL_REQUEST, ON_MERGE, SCHEDULED")
    String defaultGenerationTrigger
) {}
