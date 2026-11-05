package com.livingdocs.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a review-time revision.
 *
 * <p>Requirement #7 of the STAFF role: "fix small errors while
 * reviewing". A reviewer who spots a typo should be able to correct it
 * without sending the document back and waiting for another round trip.
 *
 * <p>The revision creates a <em>new</em> version that replaces the one
 * under review: the history stays append-only, and the version that was
 * reviewed is superseded rather than overwritten.
 */
public record ReviseVersionRequest(
        @NotBlank @Size(max = 200000) String bodyMarkdown,
        @Size(max = 500) String changeSummary
) {
}
