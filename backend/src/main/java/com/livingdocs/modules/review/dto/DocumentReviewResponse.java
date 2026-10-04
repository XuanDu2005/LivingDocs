package com.livingdocs.modules.review.dto;

import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API projection of a review record.
 */
public record DocumentReviewResponse(
        UUID id,
        UUID documentVersionId,
        UUID reviewerUserId,
        String reviewerRole,
        ReviewDecision decision,
        String comment,
        OffsetDateTime decidedAt
) {
    public static DocumentReviewResponse from(DocumentReview r) {
        return new DocumentReviewResponse(
                r.getId(),
                r.getDocumentVersionId(),
                r.getReviewerUserId(),
                r.getReviewerRole(),
                r.getDecision(),
                r.getComment(),
                r.getDecidedAt()
        );
    }
}