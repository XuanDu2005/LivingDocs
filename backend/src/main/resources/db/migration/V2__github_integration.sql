-- LivingDocs V2 — GitHub integration tables.
--
-- This migration introduces the persistent schema required by Phase 2
-- (GitHub OAuth, repository linkage, webhook ingestion, pull-request
-- tracking). All new tables are created in the public schema and reuse
-- the project's existing conventions: UUID primary keys, timestamptz
-- audit columns, named foreign keys, and dedicated indexes for the
-- access patterns used by the GitHub module.

-- ----------------------------------------------------------------------------
-- 1) Per-user GitHub connection.
--    A user can connect at most one GitHub identity; the unique constraint
--    on user_id makes that explicit and lets the API treat connections as
--    "the current user's connection".
-- ----------------------------------------------------------------------------
CREATE TABLE github_connections (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL,
    github_user_id     BIGINT       NOT NULL,
    github_login       VARCHAR(120) NOT NULL,
    access_token       VARCHAR(255) NOT NULL,
    token_type         VARCHAR(40)  NOT NULL DEFAULT 'bearer',
    scope              VARCHAR(500),
    connected_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at       TIMESTAMPTZ,
    last_synced_at     TIMESTAMPTZ,
    status             VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_github_connection_user      UNIQUE (user_id),
    CONSTRAINT uq_github_connection_github_id UNIQUE (github_user_id),
    CONSTRAINT fk_github_connection_user      FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_github_connections_login ON github_connections (github_login);

-- ----------------------------------------------------------------------------
-- 2) Repositories linked to a workspace.
--    A repository belongs to exactly one workspace and tracks its GitHub
--    identity (owner/name) along with operational metadata such as the
--    default branch and the most recent successful sync.
-- ----------------------------------------------------------------------------
CREATE TABLE repositories (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID         NOT NULL,
    github_id          BIGINT       NOT NULL,
    owner              VARCHAR(120) NOT NULL,
    name               VARCHAR(200) NOT NULL,
    full_name          VARCHAR(320) NOT NULL,
    default_branch     VARCHAR(120) NOT NULL DEFAULT 'main',
    html_url           VARCHAR(500),
    description        VARCHAR(500),
    is_private         BOOLEAN      NOT NULL DEFAULT FALSE,
    status             VARCHAR(24)  NOT NULL DEFAULT 'CONNECTED',
    connected_by       UUID         NOT NULL,
    connected_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_synced_at     TIMESTAMPTZ,
    CONSTRAINT uq_repositories_github_id     UNIQUE (github_id),
    CONSTRAINT uq_repositories_workspace_gh  UNIQUE (workspace_id, github_id),
    CONSTRAINT fk_repositories_workspace     FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_repositories_connected_by  FOREIGN KEY (connected_by) REFERENCES users (id)       ON DELETE RESTRICT
);

CREATE INDEX idx_repositories_workspace ON repositories (workspace_id);
CREATE INDEX idx_repositories_owner_name ON repositories (owner, name);

-- ----------------------------------------------------------------------------
-- 3) Webhook events.
--    Every delivery from GitHub is recorded (after signature validation)
--    so we can replay, audit, and re-process events. The (delivery_id)
--    unique constraint prevents double-processing the same GitHub
--    delivery; (event_type, repository_id) speeds up the per-repo query
--    used by the PR ingestion job.
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_events (
    id                 UUID PRIMARY KEY,
    repository_id      UUID,
    event_type         VARCHAR(40)  NOT NULL,
    delivery_id        VARCHAR(120) NOT NULL,
    action             VARCHAR(40),
    payload            JSONB        NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at       TIMESTAMPTZ,
    processing_status  VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    processing_error   VARCHAR(1000),
    CONSTRAINT uq_webhook_events_delivery UNIQUE (delivery_id),
    CONSTRAINT fk_webhook_events_repo     FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE SET NULL
);

CREATE INDEX idx_webhook_events_repo      ON webhook_events (repository_id);
CREATE INDEX idx_webhook_events_type_repo ON webhook_events (event_type, repository_id);
CREATE INDEX idx_webhook_events_status    ON webhook_events (processing_status);

-- ----------------------------------------------------------------------------
-- 4) Pull requests.
--    Mirrors the PRs ingested from a connected repository. The unique
--    constraint on (repository_id, github_pr_number) prevents duplicates
--    when the same PR is delivered through multiple webhooks.
-- ----------------------------------------------------------------------------
CREATE TABLE pull_requests (
    id                 UUID PRIMARY KEY,
    repository_id      UUID         NOT NULL,
    github_pr_number   BIGINT       NOT NULL,
    title              VARCHAR(500) NOT NULL,
    state              VARCHAR(24)  NOT NULL,
    author_login       VARCHAR(120),
    head_branch        VARCHAR(200) NOT NULL,
    base_branch        VARCHAR(200) NOT NULL,
    head_sha           VARCHAR(80)  NOT NULL,
    html_url           VARCHAR(500),
    is_draft           BOOLEAN      NOT NULL DEFAULT FALSE,
    opened_at          TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    closed_at          TIMESTAMPTZ,
    merged_at          TIMESTAMPTZ,
    last_ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pull_requests_repo_number UNIQUE (repository_id, github_pr_number),
    CONSTRAINT fk_pull_requests_repo        FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE CASCADE
);

CREATE INDEX idx_pull_requests_repo_state ON pull_requests (repository_id, state);
CREATE INDEX idx_pull_requests_head_sha   ON pull_requests (head_sha);
