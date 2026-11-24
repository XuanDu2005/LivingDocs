-- LivingDocs V59 — track repository-level "scan the docs and queue update
-- proposals" jobs.
--
-- The problem this migration solves
-- -------------------------------
-- The "sync documentation" button used to run the whole scan inside a
-- single HTTP request:
--
--   1. walk every Markdown file of the repository (one GitHub call per
--      file),
--   2. ask the AI to propose an update for every file that drifted
--      (one LLM call per drifted file, each taking 5-30 seconds),
--   3. queue a review proposal for each proposal the AI produced.
--
-- On a repository with a handful of drifted files that is minutes of
-- work — far beyond any sensible HTTP timeout. The browser gave up
-- after two minutes and showed "the scan timed out", while the server
-- kept working and kept spending the LLM budget. The user saw a
-- failure; the platform did the work anyway and charged for it.
--
-- The same problem was already solved for the sibling flow: the
-- repository-wide *drift* scan runs as a tracked job
-- (`repository_drift_scan_jobs`, migration V57). This migration gives
-- the documentation scan the same treatment, so both repository-wide
-- scans behave the same way for the user.
--
-- The job tracks:
--   * which repository it covers and which branch it scans
--   * how many files were scanned / how many drifted
--   * how many proposals were queued / how many were skipped
--   * the per-file outcome, so a partial failure is visible
CREATE TABLE IF NOT EXISTS repository_doc_sync_jobs (
    id                      uuid PRIMARY KEY,
    workspace_id            uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id           uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    branch                  varchar(255),
    status                  varchar(24) NOT NULL DEFAULT 'PENDING',
    total_files             integer NOT NULL DEFAULT 0,
    scanned_files           integer NOT NULL DEFAULT 0,
    drifted_files           integer NOT NULL DEFAULT 0,
    queued_proposals        integer NOT NULL DEFAULT 0,
    skipped_proposals       integer NOT NULL DEFAULT 0,
    failed_files            integer NOT NULL DEFAULT 0,
    error_message           text,
    result_json             jsonb,
    requested_by            uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    started_at              timestamptz,
    finished_at             timestamptz
);

CREATE INDEX IF NOT EXISTS idx_repo_doc_sync_jobs_workspace
    ON repository_doc_sync_jobs (workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_repo_doc_sync_jobs_repository
    ON repository_doc_sync_jobs (repository_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_repo_doc_sync_jobs_status
    ON repository_doc_sync_jobs (status);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_repo_doc_sync_jobs_status'
    ) THEN
        ALTER TABLE repository_doc_sync_jobs
            ADD CONSTRAINT chk_repo_doc_sync_jobs_status
            CHECK (status::text = ANY (ARRAY[
                'PENDING'::varchar, 'RUNNING'::varchar, 'SUCCEEDED'::varchar,
                'PARTIAL'::varchar, 'FAILED'::varchar
            ]));
    END IF;
END $$;
