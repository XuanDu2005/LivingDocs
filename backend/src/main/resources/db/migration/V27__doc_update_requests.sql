-- DocUpdateRequest: a "documentation update proposal" generated when code
-- drifts. Lifecycle:
--   PENDING_REVIEW  → auto-created by webhook push handler
--   APPROVED        → manager/approver signed off → create PR on GitHub
--   REJECTED        → reviewer rejected the proposed content
--   APPLIED         → PR opened on GitHub (terminal state)
--
-- This is the queue that powers the "review before pushing" workflow:
-- devs see what's about to change, comment on it, and only the
-- approver's go-ahead creates a real pull request.

CREATE TABLE doc_update_requests (
    id                       UUID PRIMARY KEY,
    workspace_id             UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id            UUID         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    document_id              UUID             NULL REFERENCES documents(id) ON DELETE SET NULL,
    file_path                VARCHAR(512) NOT NULL,
    trigger_commit_sha       VARCHAR(80),
    trigger_branch           VARCHAR(255),
    current_content          TEXT,
    proposed_content         TEXT             NOT NULL,
    diff_summary             VARCHAR(1000),
    status                   VARCHAR(24)      NOT NULL DEFAULT 'PENDING_REVIEW',
    requested_by_user_id     UUID             NULL REFERENCES users(id) ON DELETE SET NULL,
    requested_at             TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    reviewed_by_user_id      UUID             NULL REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at              TIMESTAMPTZ      NULL,
    review_comment           TEXT,
    applied_at               TIMESTAMPTZ      NULL,
    applied_pr_url           VARCHAR(1000)    NULL,
    created_at               TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_doc_update_status
        CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED','APPLIED'))
);

-- Hot query: "what's still waiting for my team to review?"
CREATE INDEX idx_doc_update_pending
    ON doc_update_requests(workspace_id, repository_id, status, requested_at DESC);

-- Helpful for an individual repo's history view.
CREATE INDEX idx_doc_update_repo_status
    ON doc_update_requests(repository_id, status);

-- Reusable trigger function (mirrors the pattern used by other tables in
-- the schema). Creating it idempotently so re-running the migration is
-- safe.
CREATE OR REPLACE FUNCTION set_doc_update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Auto-update updated_at on every UPDATE.
CREATE TRIGGER trg_doc_update_requests_updated_at
    BEFORE UPDATE ON doc_update_requests
    FOR EACH ROW
    EXECUTE FUNCTION set_doc_update_updated_at();
