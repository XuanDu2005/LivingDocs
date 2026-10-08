package com.livingdocs.modules.workspace.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

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
    float aiConfidenceThreshold
) {}
