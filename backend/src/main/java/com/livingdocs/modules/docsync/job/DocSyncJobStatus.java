package com.livingdocs.modules.docsync.job;

/**
 * The life-cycle of a repository-wide documentation sync.
 *
 * <p>Mirrors {@code RepositoryDriftScanStatus} on purpose: the UI
 * polls both kinds of job the same way, and a user who has seen one
 * should not have to learn a second vocabulary for the other.
 *
 * <p>{@code PARTIAL} exists because a sync covers <em>many</em> files.
 * A run that proposed updates for 5 of 8 drifted files is neither a
 * success (the user asked about all 8) nor a failure (5 usable
 * proposals exist). Collapsing the two states would hide the
 * difference.
 */
public enum DocSyncJobStatus {
    /** The job row exists; the worker has not picked it up yet. */
    PENDING,
    /** The worker is scanning the repository and proposing updates. */
    RUNNING,
    /** Every file was processed without error. */
    SUCCEEDED,
    /** Some files were processed, others could not be. */
    PARTIAL,
    /** Nothing could be processed. */
    FAILED
}
