-- LivingDocs V57 — track repository-level ("scan the whole repository for
-- documentation drift") jobs.
--
-- The drift detection flow that exists today is document-scoped: the
-- endpoint POST /workspaces/{id}/drift-alerts/detect takes a mandatory
-- `documentId`, and the UI's "Run scan" dialog forces the user to pick
-- one document before the button becomes clickable.
--
-- That shape does not answer the question the feature is actually for.
-- The interesting question is "which documents of this repository no
-- longer match the code?", and answering it one document at a time
-- means the user has to know in advance which document to look at —
-- which is precisely what they do not know when the documentation has
-- drifted.
--
-- This migration adds the table for the repository-scoped flow: one job
-- per scan, which walks every document of the repository, compares each
-- one against the current code, and files the drift alerts it finds.
--
-- The job is tracked the same way as a repository analysis job so the
-- UI can poll it: the row is created synchronously (the endpoint answers
-- 202) and mutated in place by the background worker.
--
-- A job tracks:
--   * which repository it covers and which pull request anchors it
--   * how many documents were scanned / how many produced alerts
--   * the per-document outcome, so a partial failure is visible
CREATE TABLE IF NOT EXISTS repository_drift_scan_jobs (
    id                      uuid PRIMARY KEY,
    workspace_id            uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id           uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pull_request_id         uuid REFERENCES pull_requests(id) ON DELETE SET NULL,
    status                  varchar(24) NOT NULL DEFAULT 'PENDING',
    total_documents         integer NOT NULL DEFAULT 0,
    scanned_documents       integer NOT NULL DEFAULT 0,
    documents_with_drift    integer NOT NULL DEFAULT 0,
    alerts_created          integer NOT NULL DEFAULT 0,
    failed_documents        integer NOT NULL DEFAULT 0,
    error_message           text,
    result_json             jsonb,
    requested_by            uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    started_at              timestamptz,
    finished_at             timestamptz
);

CREATE INDEX IF NOT EXISTS idx_repo_drift_scan_jobs_workspace
    ON repository_drift_scan_jobs (workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_repo_drift_scan_jobs_repository
    ON repository_drift_scan_jobs (repository_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_repo_drift_scan_jobs_status
    ON repository_drift_scan_jobs (status);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_repo_drift_scan_jobs_status'
    ) THEN
        ALTER TABLE repository_drift_scan_jobs
            ADD CONSTRAINT chk_repo_drift_scan_jobs_status
            CHECK (status::text = ANY (ARRAY[
                'PENDING'::varchar, 'RUNNING'::varchar, 'SUCCEEDED'::varchar,
                'PARTIAL'::varchar, 'FAILED'::varchar
            ]));
    END IF;
END $$;
