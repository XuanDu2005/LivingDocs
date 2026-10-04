-- LivingDocs V1 — initial schema: users + workspaces.
--
-- This migration introduces the persistent schema for the first business
-- feature set (auth, user, workspace). Future phases add tables for
-- repositories, documents, drift analyses, etc.

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    display_name    VARCHAR(120),
    password_hash   VARCHAR(100) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE workspaces (
    id           UUID PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    slug         VARCHAR(140) NOT NULL,
    description  VARCHAR(500),
    owner_id     UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspaces_slug UNIQUE (slug),
    CONSTRAINT fk_workspaces_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE TABLE workspace_members (
    id            UUID PRIMARY KEY,
    workspace_id  UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    joined_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_member UNIQUE (workspace_id, user_id),
    CONSTRAINT fk_wm_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_wm_user      FOREIGN KEY (user_id)      REFERENCES users (id)       ON DELETE CASCADE
);

CREATE INDEX idx_workspace_members_user      ON workspace_members (user_id);
CREATE INDEX idx_workspace_members_workspace ON workspace_members (workspace_id);