-- LivingDocs V13 — AI usage tracking and rate limits.
--
-- Phase 4: Track AI token usage and request counts per workspace
-- to support budget enforcement and rate limiting.

CREATE TABLE ai_usage_stats (
    id              UUID         PRIMARY KEY,
    workspace_id    UUID         NOT NULL,
    date            DATE         NOT NULL,
    total_tokens    BIGINT       NOT NULL DEFAULT 0,
    request_count   INTEGER      NOT NULL DEFAULT 0,
    estimated_cost  NUMERIC(12,4) NOT NULL DEFAULT 0,
    last_request_at TIMESTAMPTZ  NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_ai_usage_stats UNIQUE (workspace_id, date),
    CONSTRAINT fk_ai_usage_stats_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX idx_ai_usage_stats_workspace_date
    ON ai_usage_stats (workspace_id, date DESC);

-- Add rate limit fields to workspace_settings (one per workspace).
ALTER TABLE workspace_settings
    ADD COLUMN daily_token_limit  BIGINT NULL,
    ADD COLUMN monthly_token_limit BIGINT NULL,
    ADD COLUMN rate_limit_per_min INTEGER NULL;
