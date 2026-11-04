package com.livingdocs.modules.review.dto;

import com.livingdocs.modules.version.model.VersionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One version the caller submitted for review, together with the verdict
 * it received (or is still waiting for).
 *
 * <p>This is the projection behind the "my submissions" bucket of the
 * review queue. It answers the question an author actually has: <em>what
 * happened to the draft I handed over?</em>
 *
 * <p>The {@code stage} field is a flattened, UI-friendly summary of the
 * workflow. It collapses two pieces of state — the version status and
 * the latest review verdict — into one label so the front-end does not
 * have to re-implement the transition rules:
 *
 * <ul>
 *     <li>{@code WAITING_STAFF} — submitted, nobody has reviewed it yet.</li>
 *     <li>{@code WAITING_MANAGER} — Staff approved, waiting for the
 *         Manager tier.</li>
 *     <li>{@code CHANGES_REQUESTED} — a reviewer asked for changes.</li>
 *     <li>{@code REJECTED} — a reviewer rejected the version.</li>
 *     <li>{@code APPROVED} — fully approved, waiting to be published.</li>
 *     <li>{@code PUBLISHED} — the version is the document's head.</li>
 * </ul>
 */
public record SubmittedVersionResponse(
        UUID versionId,
        UUID documentId,
        String documentTitle,
        String docType,
        Integer versionNumber,
        VersionStatus versionStatus,
        String changeSummary,
        Float confidenceScore,
        OffsetDateTime submittedAt,
        String stage,
        String latestDecision,
        String latestReviewerRole,
        String latestReviewerName,
        String latestComment,
        OffsetDateTime latestDecidedAt
) {
}
