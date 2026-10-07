package com.livingdocs.modules.ai.indexing;

/**
 * Lifecycle of an {@link IndexJob}.
 *
 * <pre>
 *   PENDING  -- enqueued, not picked up by a worker yet
 *   RUNNING  -- a worker is actively processing the job
 *   COMPLETED -- job finished and all targets were indexed
 *   FAILED   -- job stopped because of an unrecoverable error
 *   CANCELLED -- job was cancelled by the user before completion
 * </pre>
 */
public enum IndexJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}