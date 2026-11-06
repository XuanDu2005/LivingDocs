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
 *
 * <p>{@code reviewerName} is the display name of the account that made
 * the call. It is resolved by the service, because a Manager reading
 * the Staff comments of a version has to know <em>who</em> wrote them;
 * the raw user id alone is not readable. It is {@code null} when the
 * reviewer account no longer exists.
 *
 * <p>{@code supersededAt} is the stamp of a verdict that a later
 * verdict in the same tier replaced. It is {@code null} for a verdict
 * that is still the current opinion of its tier. The UI uses it to
 * strike through a withdrawn verdict instead of presenting it as if it
 * still counted.
 */
public record DocumentReviewResponse(
        UUID id,
        UUID documentVersionId,
        UUID reviewerUserId,
        String reviewerName,
        String reviewerRole,
        ReviewDecision decision,
        String comment,
        String sectionId,
        String sectionHeading,
        String sectionAnchor,
        OffsetDateTime decidedAt,
        OffsetDateTime supersededAt
) {
    public static DocumentReviewResponse from(DocumentReview r) {
        return from(r, null);
    }

    public static DocumentReviewResponse from(DocumentReview r, String reviewerName) {
        return new DocumentReviewResponse(
                r.getId(),
                r.getDocumentVersionId(),
                r.getReviewerUserId(),
                reviewerName,
                r.getReviewerRole(),
                r.getDecision(),
                r.getComment(),
                r.getSectionId(),
                r.getSectionHeading(),
                r.getSectionAnchor(),
                r.getDecidedAt(),
                r.getSupersededAt()
        );
    }
}
