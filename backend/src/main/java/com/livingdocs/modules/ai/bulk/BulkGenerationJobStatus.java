package com.livingdocs.modules.ai.bulk;

/**
 * Lifecycle of a {@link BulkGenerationJob}.
 *
 * <pre>
 *   PENDING   -- enqueued, the worker has not picked it up yet
 *   RUNNING   -- the worker is generating documentation for the entities
 *   SUCCEEDED -- every entity was processed without error
 *   PARTIAL   -- some entities failed but at least one document was written
 *   FAILED    -- the job could not run at all
 * </pre>
 */
public enum BulkGenerationJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED
}
