-- LivingDocs V8 — External integrations (GitHub, GitLab, Jira, Slack).
--
-- This migration introduces the persistent schema for the integrations
-- module. The module replaces the per-platform ad-hoc code paths with a
-- single connection/secret store that can carry credentials for any of
-- the four supported providers. Webhook events are persisted with their
-- original payload so they can be inspected even after processing.
--
-- Conventions follow the rest of the schema: UUID primary keys, timestamptz
-- audit columns, named foreign keys, dedicated indexes for the common access
-- paths. The tokens are stored as Base64-encoded AES-GCM ciphertext so the
-- encryption helper used for AI provider keys can be reused directly.

-- ----------------------------------------------------------------------------
-- 1) Integration connections.
--    A single row per logical link between LivingDocs and an external
--    provider. Connections may be scoped to a workspace (for SCM/Jira) or
--    platform-wide (for Slack). The unique index on
--    (provider, workspace_id, external_account) makes "is this account
--    already linked here?" a single index lookup.
-- ----------------------------------------------------------------------------
CREATE TABLE integration_connections (
    id                        UUID         PRIMARY KEY,

    -- Provider code (GITHUB | GITLAB | JIRA | SLACK). Persisted as text
    -- so adding a fifth provider later does not require a migration.
    provider                  VARCHAR(16)  NOT NULL,

    -- NULL = platform-wide (Slack, global Jira). Non-null = workspace-scoped.
    workspace_id              UUID         NULL,

    -- Human-readable label shown in the admin UI (e.g. "Acme Engineering").
    display_name              VARCHAR(255) NOT NULL,

    -- Identifier the user/dev knows the account by (GitHub login, Slack
    -- team, Jira site URL, GitLab group, ...). Used for de-duplication and
    -- to make the connection row scannable in pgcli.
    external_account          VARCHAR(255) NOT NULL,

    -- Optional base URL for self-hosted providers (GitLab, Jira). NULL
    -- means use the vendor default (api.github.com / slack.com / ...).
    base_url                  VARCHAR(500) NULL,

    -- Granted OAuth scopes or API permission names (CSV).
    scopes                    TEXT         NULL,

    -- Encrypted at rest with the platform AES-GCM helper.
    access_token_encrypted    TEXT         NULL,
    refresh_token_encrypted   TEXT         NULL,

    -- Webhook signing secret (used to verify inbound HMAC deliveries).
    -- Slack stores its "signing secret" here; GitHub stores the repo
    -- secret; GitLab/Jira store their shared token.
    webhook_secret_encrypted  TEXT         NULL,

    -- External identifier for the webhook so we can update it on revoke.
    webhook_external_id       VARCHAR(128) NULL,

    -- Destination channel for outbound Slack notifications. Slack-only.
    default_channel           VARCHAR(120) NULL,

    -- Lifecycle. ACTIVE = can send/receive. REVOKED = disabled by admin.
    -- ERROR = last health-check failed (kept for display, no auto-retry).
    status                    VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    -- Last successful health-check (e.g. SlackNotifier test, GitHub ping).
    last_used_at              TIMESTAMPTZ  NULL,
    last_synced_at            TIMESTAMPTZ  NULL,

    connected_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_integration_connections_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX idx_integration_connections_provider
    ON integration_connections (provider);
CREATE INDEX idx_integration_connections_workspace
    ON integration_connections (workspace_id);
CREATE INDEX idx_integration_connections_status
    ON integration_connections (status);

-- A connection is uniquely identified by the external identifier plus the
-- provider. Two workspaces may link the same GitHub org, but a single
-- workspace cannot link it twice.
CREATE UNIQUE INDEX uq_integration_connections_provider_ws_account
    ON integration_connections (provider, workspace_id, external_account);

-- ----------------------------------------------------------------------------
-- 2) Integration webhook deliveries.
--    Append-only log of every inbound webhook received (one row per
--    HTTP delivery, regardless of processing success). The (provider,
--    delivery_id) unique constraint is the idempotency key for replays.
-- ----------------------------------------------------------------------------
CREATE TABLE integration_events (
    id                 UUID         PRIMARY KEY,

    -- NULL when the delivery could not be matched to a known connection.
    connection_id      UUID         NULL,

    provider           VARCHAR(16)  NOT NULL,
    event_type         VARCHAR(64)  NOT NULL,
    delivery_id        VARCHAR(160) NOT NULL,
    action             VARCHAR(40)  NULL,

    -- Lightweight projection of the payload (e.g. PR title, issue key,
    -- channel id). The raw body is intentionally NOT stored here to keep
    -- this table small — only what the admin UI needs to render the
    -- delivery row.
    payload_summary     JSONB        NULL,

    -- PENDING -> PROCESSED | FAILED | IGNORED.
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    error_message      VARCHAR(1000) NULL,

    received_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at       TIMESTAMPTZ  NULL,

    CONSTRAINT fk_integration_events_connection
        FOREIGN KEY (connection_id) REFERENCES integration_connections (id) ON DELETE SET NULL
);

CREATE INDEX idx_integration_events_received
    ON integration_events (received_at DESC);
CREATE INDEX idx_integration_events_connection
    ON integration_events (connection_id, received_at DESC);
CREATE INDEX idx_integration_events_provider
    ON integration_events (provider, received_at DESC);

-- Idempotency: re-delivery of the same (provider, delivery_id) is a no-op.
CREATE UNIQUE INDEX uq_integration_events_provider_delivery
    ON integration_events (provider, delivery_id);