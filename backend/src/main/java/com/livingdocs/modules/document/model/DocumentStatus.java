package com.livingdocs.modules.document.model;

/**
 * Workflow states a document (or a specific version) can be in.
 *
 * <ul>
 *     <li>{@link #DRAFT} — author is still editing, not yet submitted for review.</li>
 *     <li>{@link #IN_REVIEW} — Staff is reviewing.</li>
 *     <li>{@link #APPROVED} — Manager approved; awaiting publish or already published.</li>
 *     <li>{@link #PUBLISHED} — current canonical version is visible to readers.</li>
 *     <li>{@link #REJECTED} — review failed; back to the author.</li>
 *     <li>{@link #ARCHIVED} — no longer maintained.</li>
 * </ul>
 */
public enum DocumentStatus {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    PUBLISHED,
    REJECTED,
    ARCHIVED
}