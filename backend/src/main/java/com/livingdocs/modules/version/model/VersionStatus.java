package com.livingdocs.modules.version.model;

/**
 * Lifecycle of a single version snapshot. Versions are immutable; their
 * {@code status} reflects how far they have progressed through the review
 * workflow.
 *
 * <ul>
 *     <li>{@link #PENDING} — just created (AI generation, AI auto-update or
 *         a human edit), not yet picked up by a reviewer.</li>
 *     <li>{@link #IN_REVIEW} — a Staff reviewer is looking at it.</li>
 *     <li>{@link #APPROVED} — Manager approved but the document is not yet
 *         promoted to head.</li>
 *     <li>{@link #PUBLISHED} — this version is the canonical head of the
 *         document.</li>
 *     <li>{@link #REJECTED} — review failed; the version is kept for
 *         audit but never becomes head.</li>
 *     <li>{@link #SUPERSEDED} — was published but later replaced.</li>
 * </ul>
 */
public enum VersionStatus {
    PENDING,
    IN_REVIEW,
    APPROVED,
    PUBLISHED,
    REJECTED,
    SUPERSEDED
}