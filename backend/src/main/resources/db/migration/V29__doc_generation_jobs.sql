-- LivingDocs V29 — AI generation job tracking.
--
-- The AI generation pipeline is asynchronous: the HTTP request queues a
-- Kafka event, a worker calls the AI service, and a second worker
-- persists the resulting document version. This table is the durable
-- record of that pipeline so the UI can show "AI is generating…", and
-- so a redelivered Kafka message never creates a duplicate version.
--
-- The job's own id doubles as the `correlation_id` carried by every
-- event in the flow, which makes the whole chain traceable end-to-end.

CREATE TABLE doc_generation_jobs (
    id                  UUID         PRIMARY KEY,
    workspace_id        UUID         NOT NULL,
    document_id         UUID         NOT NULL,
    correlation_id      UUID         NOT NULL,
    requested_by        UUID,
    template            VARCHAR(140),
    status              VARCHAR(24)  NOT NULL DEFAULT 'RUNNING',
    document_version_id UUID,
    error_message       TEXT,
    prompt_tokens       INTEGER      NOT NULL DEFAULT 0,
    completion_tokens   INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uq_doc_gen_jobs_correlation UNIQUE (correlation_id),
    CONSTRAINT chk_doc_gen_jobs_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT fk_doc_gen_jobs_workspace FOREIGN KEY (workspace_id)        REFERENCES workspaces (id)       ON DELETE CASCADE,
    CONSTRAINT fk_doc_gen_jobs_document FOREIGN KEY (document_id)         REFERENCES documents (id)        ON DELETE CASCADE,
    CONSTRAINT fk_doc_gen_jobs_requester FOREIGN KEY (requested_by)       REFERENCES users (id)            ON DELETE SET NULL,
    CONSTRAINT fk_doc_gen_jobs_version   FOREIGN KEY (document_version_id) REFERENCES document_versions (id) ON DELETE SET NULL
);

CREATE INDEX idx_doc_gen_jobs_document  ON doc_generation_jobs (document_id);
CREATE INDEX idx_doc_gen_jobs_workspace ON doc_generation_jobs (workspace_id);
CREATE INDEX idx_doc_gen_jobs_status    ON doc_generation_jobs (status);
CREATE INDEX idx_doc_gen_jobs_created   ON doc_generation_jobs (created_at DESC);
