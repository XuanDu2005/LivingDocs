package com.livingdocs.modules.review.dto;

import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API projection of a review record.
 *
 * <p>The {@code section*} fields are the anchor of a section-targeted
 * comment — they are {@code null} when the reviewer commented on the
 * document as a whole.
 */
public record DocumentReviewResponse(
        UUID id,
        UUID documentVersionId,
        UUID reviewerUserId,
        String reviewerRole,
        ReviewDecision decision,
        String comment,
        String sectionId,
        String sectionHeading,
        String sectionAnchor,
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
                r.getSectionId(),
                r.getSectionHeading(),
                r.getSectionAnchor(),
                r.getDecidedAt()
        );
    }
}
