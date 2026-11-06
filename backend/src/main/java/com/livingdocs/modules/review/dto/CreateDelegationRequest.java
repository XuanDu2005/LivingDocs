package com.livingdocs.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body of "delegate the review/approval work to somebody else".
 *
 * <p>The tier is explicit: a delegation has to state <em>what</em> is
 * being delegated. A Staff delegation and a Manager delegation are two
 * different acts, and the delegate is authorised for exactly one of
 * them.
 */
public record CreateDelegationRequest(
        @NotNull(message = "A delegate is required")
        java.util.UUID toUserId,

        @NotBlank(message = "A tier is required")
        @Size(max = 16)
        String tier,

        @Size(max = 500)
        String reason
) {
}
