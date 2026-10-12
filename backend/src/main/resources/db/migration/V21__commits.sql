-- LivingDocs V21 — Commits table for ingested GitHub commits.
--
-- Backed by com.livingdocs.modules.github.model.Commit. The (repository_id,
-- sha) unique constraint makes push re-deliveries idempotent.

CREATE TABLE IF NOT EXISTS commits (
    id              UUID PRIMARY KEY,
    repository_id   UUID NOT NULL,
    sha             VARCHAR(80) NOT NULL,
    message         VARCHAR(4000),
    author_name     VARCHAR(200),
    author_email    VARCHAR(200),
    authored_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    committed_at    TIMESTAMP WITH TIME ZONE,
    url             VARCHAR(500),
    branch          VARCHAR(200),
    ingested_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_commits_repo_sha UNIQUE (repository_id, sha)
);

CREATE INDEX IF NOT EXISTS idx_commits_repo          ON commits (repository_id);
CREATE INDEX IF NOT EXISTS idx_commits_authored_at  ON commits (authored_at DESC);
CREATE INDEX IF NOT EXISTS idx_commits_author_email ON commits (author_email);

ALTER TABLE commits
    ADD CONSTRAINT fk_commits_repository
    FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE;
