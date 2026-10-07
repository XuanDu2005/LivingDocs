-- LivingDocs V7 — Indexing jobs.
--
-- Phase 2d of the platform: track asynchronous indexing operations
-- against the AI knowledge base. Each row represents one attempt to
-- index a document (or a batch of documents) end-to-end: the call to
-- the AI service, chunking, embedding, and storage in the vector index.
--
-- The table records job lifecycle (PENDING -> RUNNING -> COMPLETED |
-- FAILED | CANCELLED), progress counters, and timing for observability
-- and admin tooling. The row is created synchronously when the job is
-- enqueued, then mutated by a background worker.

CREATE TABLE indexing_jobs (
    id                  UUID         PRIMARY KEY,

    -- Scope. A job always belongs to a workspace. The target is either
    -- a single document (document_id set) or the entire workspace
    -- catalogue (document_id NULL, "reindex all").
    workspace_id        UUID         NOT NULL,
    document_id         UUID         NULL,

    -- Lifecycle. Application-managed enum persisted as text for forward
    -- compatibility with new states.
    status              VARCHAR(16)  NOT NULL,

    -- Free-form kind so we can later add REMOVE_INDEX jobs, etc.
    -- Currently only INDEX (single) and REINDEX_ALL are issued.
    kind                VARCHAR(16)  NOT NULL,

    -- Counts maintained by the worker. They are best effort: a job that
    -- fails halfway through still records what was processed so admins
    -- can see how far it got.
    total_targets       INTEGER      NOT NULL DEFAULT 0,
    processed_targets   INTEGER      NOT NULL DEFAULT 0,
    chunks_indexed      INTEGER      NOT NULL DEFAULT 0,
    failed_targets      INTEGER      NOT NULL DEFAULT 0,

    -- Diagnostic message on failure or partial-success notes.
    error_message        TEXT         NULL,

    -- Bookends for SLA reporting.
    started_at          TIMESTAMPTZ  NULL,
    finished_at         TIMESTAMPTZ  NULL,

    -- Audit columns (consistent with the rest of the schema).
    created_by          UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT fk_indexing_jobs_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_indexing_jobs_document
        FOREIGN KEY (document_id)  REFERENCES documents(id)  ON DELETE CASCADE,
    CONSTRAINT fk_indexing_jobs_created_by
        FOREIGN KEY (created_by)   REFERENCES users(id)
);

-- Latest active job per workspace — used by the admin dashboard and by
-- the "is indexing already running?" guard.
CREATE INDEX idx_indexing_jobs_workspace_status
    ON indexing_jobs (workspace_id, status, created_at DESC);

-- Per-document lookup so we can answer "is there an active indexing
-- job for document X?" cheaply.
CREATE INDEX idx_indexing_jobs_document
    ON indexing_jobs (document_id, created_at DESC)
    WHERE document_id IS NOT NULL;

-- Hard ceiling so a runaway background worker can't fill the table
-- with crashed PENDINGs. We keep the last 1000 jobs per workspace.
-- Implemented as a periodic prune in the audit retention sweep; the
-- index is the access path for that sweep.
-- Charset placeholder for grep: reference link in current schema only.