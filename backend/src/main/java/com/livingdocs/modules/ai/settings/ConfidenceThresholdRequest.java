package com.livingdocs.modules.ai.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code PUT /api/v1/workspaces/{id}/ai-settings/threshold}.
 *
 * <p>The numeric range is enforced by bean validation so a malicious
 * client cannot set a value outside [0.0, 1.0].
 */
public record ConfidenceThresholdRequest(
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        Float value
) {
}