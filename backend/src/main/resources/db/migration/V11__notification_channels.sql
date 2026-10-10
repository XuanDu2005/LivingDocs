-- LivingDocs V11 — Notification channel configuration.
--
-- Phase 4: per-workspace and per-event-kind channel configuration.
-- Controls which channels (in-app, email) are active for each event
-- type. The existing notification_policies table controls on/off; this
-- new table controls delivery channels when the policy is on.

CREATE TABLE notification_channels (
    id              UUID         PRIMARY KEY,

    -- NULL = platform default. Non-null = per-workspace override.
    workspace_id    UUID         NULL,

    event_kind      VARCHAR(64)  NOT NULL,

    -- Channel toggles.
    in_app_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    email_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Optional override email recipients (comma-separated). When NULL,
    -- all workspace members (or all platform users for platform default)
    -- are notified.
    email_recipients VARCHAR(500) NULL,

    updated_by      UUID         NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_notification_channels_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

-- Idempotency: one row per (workspace_id, event_kind).
CREATE UNIQUE INDEX uq_notification_channels_workspace_kind
    ON notification_channels (workspace_id, event_kind);

CREATE INDEX idx_notification_channels_workspace
    ON notification_channels (workspace_id);
