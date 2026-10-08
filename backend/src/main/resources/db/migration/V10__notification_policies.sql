-- LivingDocs V10 — Notification policies.
--
-- Phase 2e: per-workspace (or platform-wide) toggle for every event kind
-- emitted by the platform. Missing row == policy not set == falls back
-- to the platform default row (workspace_id IS NULL). If both are
-- missing, the kind is treated as enabled (the existing behaviour).
--
-- One row per (workspace_id, event_kind) pair. The unique index is the
-- idempotency key for upserts.

CREATE TABLE notification_policies (
    id          UUID         PRIMARY KEY,

    -- NULL denotes the platform-wide default. A non-null workspace_id
    -- overrides that default for the matching workspace.
    workspace_id UUID        NULL,

    event_kind   VARCHAR(64) NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,

    updated_by   UUID        NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_notification_policies_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

-- Idempotency: one row per (workspace_id, event_kind). NULL workspace is
-- coalesced into a synthetic key (NULL is treated as DISTINCT in standard
-- SQL, but Postgres treats it as not-equal for unique matching), so we
-- emulate the constraint with this partial unique index instead.
CREATE UNIQUE INDEX uq_notification_policies_workspace_kind
    ON notification_policies (workspace_id, event_kind);

CREATE INDEX idx_notification_policies_workspace
    ON notification_policies (workspace_id);