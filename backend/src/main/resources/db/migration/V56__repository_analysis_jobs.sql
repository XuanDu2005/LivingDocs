-- LivingDocs V56 — track repository-level ("analyse the whole repository")
-- AI documentation jobs.
--
-- The bulk generation flow (V34) documents a repository *entity by
-- entity*: one LLM call per class / method, one document per entity.
-- That shape does not answer the question a new reader actually has
-- ("what is this repository, how do I run it, how is it put
-- together?"), because none of the per-entity documents carry that
-- context.
--
-- This migration adds the table for the repository-level flow: one job
-- per analysis run, which produces a small set of overview documents
-- (README, ARCHITECTURE, ONBOARDING_GUIDE, API_REFERENCE) grounded on
-- the repository as a whole — its directory tree, its dependency
-- manifests and a sample of its key source files.
--
-- The job is tracked in the same way as a bulk generation job so the
-- UI can poll it: the row is created synchronously (the endpoint
-- answers 202) and mutated in place by the background worker.
--
-- A job tracks:
--   * which repository it covers and which document types it targets
--   * how many files the scan found in total
--   * how many documents were produced / failed
--   * the resulting per-document outcome, so a partial failure is visible
CREATE TABLE IF NOT EXISTS repository_analysis_jobs (
    id                      uuid PRIMARY KEY,
    workspace_id            uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id           uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    doc_types               varchar(280),
    status                  varchar(24) NOT NULL DEFAULT 'PENDING',
    total_files             integer NOT NULL DEFAULT 0,
    scanned_files           integer NOT NULL DEFAULT 0,
    processed_documents     integer NOT NULL DEFAULT 0,
    generated_documents     integer NOT NULL DEFAULT 0,
    failed_documents        integer NOT NULL DEFAULT 0,
    error_message           text,
    result_json             jsonb,
    requested_by            uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    started_at              timestamptz,
    finished_at             timestamptz
);

CREATE INDEX IF NOT EXISTS idx_repo_analysis_jobs_workspace
    ON repository_analysis_jobs (workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_repo_analysis_jobs_repository
    ON repository_analysis_jobs (repository_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_repo_analysis_jobs_status
    ON repository_analysis_jobs (status);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_repo_analysis_jobs_status'
    ) THEN
        ALTER TABLE repository_analysis_jobs
            ADD CONSTRAINT chk_repo_analysis_jobs_status
            CHECK (status::text = ANY (ARRAY[
                'PENDING'::varchar, 'RUNNING'::varchar, 'SUCCEEDED'::varchar,
                'PARTIAL'::varchar, 'FAILED'::varchar
            ]));
    END IF;
END $$;
