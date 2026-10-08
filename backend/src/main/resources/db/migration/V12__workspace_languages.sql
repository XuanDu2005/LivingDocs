-- LivingDocs V12 — Supported programming languages per workspace.
--
-- Phase 4: track which programming languages are enabled for AI
-- code analysis + documentation generation per workspace, plus
-- optional per-language custom prompt overrides.

CREATE TABLE workspace_languages (
    id              UUID         PRIMARY KEY,
    workspace_id    UUID         NOT NULL,
    language_code   VARCHAR(40)  NOT NULL,
    language_name   VARCHAR(80)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    custom_prompt   TEXT         NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_workspace_languages UNIQUE (workspace_id, language_code),
    CONSTRAINT fk_workspace_languages_ws
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX idx_workspace_languages_ws ON workspace_languages (workspace_id);
