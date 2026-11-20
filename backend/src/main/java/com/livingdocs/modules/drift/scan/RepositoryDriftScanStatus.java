package com.livingdocs.modules.drift.scan;

/**
 * The life-cycle of a repository-level drift scan.
 *
 * <p>Mirrors {@code RepositoryAnalysisJobStatus} on purpose: the UI
 * polls both kinds of job the same way, and a user who has seen one
 * should not have to learn a second vocabulary for the other.
 *
 * <p>{@code PARTIAL} exists because a scan covers <em>many</em>
 * documents. A run that compared 12 of 15 documents is neither a
 * success (the user asked about all 15) nor a failure (12 usable
 * answers exist). Collapsing the two states would hide the difference.
 */
public enum RepositoryDriftScanStatus {
    /** The job row exists; the worker has not picked it up yet. */
    PENDING,
    /** The worker is comparing the documents of the repository. */
    RUNNING,
    /** Every document was compared without error. */
    SUCCEEDED,
    /** Some documents were compared, others could not be compared. */
    PARTIAL,
    /** Nothing could be compared. */
    FAILED
}
