package com.livingdocs.modules.ai.analysis;

/**
 * The life-cycle of a repository-level analysis run.
 *
 * <p>Mirrors {@code BulkGenerationJobStatus} on purpose: the UI polls
 * both kinds of job the same way, and a user who has seen one should
 * not have to learn a second vocabulary for the other.
 *
 * <p>{@code PARTIAL} exists because a repository-level run produces
 * <em>several</em> documents — one per doc type. A run that wrote three
 * of four documents is neither a success (the user asked for four) nor a
 * failure (three usable documents exist). Collapsing the two states
 * would hide the difference.
 */
public enum RepositoryAnalysisJobStatus {
    /** The job row exists; the worker has not picked it up yet. */
    PENDING,
    /** The worker is scanning the repository and generating documents. */
    RUNNING,
    /** Every requested document type produced a document. */
    SUCCEEDED,
    /** Some document types produced a document, others failed. */
    PARTIAL,
    /** Nothing was produced. */
    FAILED
}
