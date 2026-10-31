-- LivingDocs V34 — track bulk ("generate all docs") AI generation jobs.
--
-- Generating documentation for every undocumented code entity in a
-- repository means one LLM call per entity. With a dozen entities that
-- is well over a minute of work, far too long for a synchronous HTTP
-- request. The endpoint now enqueues a job and answers 202; this table
-- records the job so the UI can poll its progress.
--
-- A job tracks:
--   * which repository and template it covers
--   * how many entities were found in total
--   * how many were processed / skipped / failed so far
--   * the resulting per-entity outcome, so a partial failure is visible
CREATE TABLE IF NOT EXISTS bulk_generation_jobs (
    id                      uuid PRIMARY KEY,
    workspace_id            uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id           uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    template                varchar(140),
    status                  varchar(24) NOT NULL DEFAULT 'PENDING',
    total_entities          integer NOT NULL DEFAULT 0,
    processed_entities      integer NOT NULL DEFAULT 0,
    generated_documents     integer NOT NULL DEFAULT 0,
    skipped_entities        integer NOT NULL DEFAULT 0,
    failed_entities         integer NOT NULL DEFAULT 0,
    error_message           text,
    result_json             jsonb,
    requested_by            uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    started_at              timestamptz,
    finished_at             timestamptz
);

CREATE INDEX IF NOT EXISTS idx_bulk_gen_jobs_workspace
    ON bulk_generation_jobs (workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_bulk_gen_jobs_repository
    ON bulk_generation_jobs (repository_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_bulk_gen_jobs_status
    ON bulk_generation_jobs (status);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_bulk_gen_jobs_status'
    ) THEN
        ALTER TABLE bulk_generation_jobs
            ADD CONSTRAINT chk_bulk_gen_jobs_status
            CHECK (status::text = ANY (ARRAY[
                'PENDING'::varchar, 'RUNNING'::varchar, 'SUCCEEDED'::varchar,
                'PARTIAL'::varchar, 'FAILED'::varchar
            ]));
    END IF;
END $$;
