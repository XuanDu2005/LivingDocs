package com.livingdocs.modules.review.dto;

import com.livingdocs.modules.review.model.ReviewDecision;
import jakarta.validation.constraints.Size;

/**
 * Body for recording a review verdict.
 *
 * <p>The three {@code section*} fields anchor the comment to one
 * specific part of the document (requirement #8 of the STAFF role).
 * A document is markdown, so a "section" is a markdown heading:
 * <ul>
 *   <li>{@code sectionId} — the slug of the heading ({@code overview});</li>
 *   <li>{@code sectionHeading} — the heading text ({@code Overview});</li>
 *   <li>{@code sectionAnchor} — a GitHub-style anchor ({@code #overview})
 *       so the UI can deep-link to it.</li>
 * </ul>
 *
 * <p>All three are optional: a review that comments on the document as
 * a whole leaves them {@code null}.
 */
public record SubmitReviewRequest(
        ReviewDecision decision,
        @Size(max = 4000) String comment,
        @Size(max = 120) String sectionId,
        @Size(max = 255) String sectionHeading,
        @Size(max = 255) String sectionAnchor
) {
    public SubmitReviewRequest(ReviewDecision decision, String comment) {
        this(decision, comment, null, null, null);
    }
}
