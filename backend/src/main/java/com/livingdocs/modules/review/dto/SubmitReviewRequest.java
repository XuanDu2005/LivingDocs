package com.livingdocs.modules.review.dto;

import com.livingdocs.modules.review.model.ReviewDecision;
import jakarta.validation.constraints.Size;

/**
 * Body for recording a review verdict.
 */
public record SubmitReviewRequest(
        ReviewDecision decision,
        @Size(max = 4000) String comment
) {}