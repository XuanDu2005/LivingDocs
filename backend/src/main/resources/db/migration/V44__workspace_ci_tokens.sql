-- LivingDocs V44 — per-workspace CI tokens.
--
-- Problem this migration solves
-- ---------------------------
-- The merge gate (workspace_settings.merge_policy_critical = BLOCK) was
-- implemented but never enforced:
--
--   * the generated GitHub Actions workflow asked for its behaviour
--     through the `?blockOnCritical=true` query parameter, so the
--     workspace policy was never consulted — the workflow failed the
--     build unconditionally instead of asking LivingDocs;
--   * the workflow's only call was a POST to /api/v1/github/webhook with
--     a `drift.scan` event, but that endpoint requires an
--     `X-GitHub-Event` header and verifies an HMAC signature — a
--     workflow has neither, so the call was rejected with 401 and the
--     scan never ran;
--   * nothing in the CI path ever asked LivingDocs whether the pull
--     request could be merged.
--
-- To make the gate real, the CI job has to call back into LivingDocs.
-- That call cannot use a user JWT (a workflow has no user session), so
-- each workspace gets its own long-lived CI token.
--
-- Why the token is hashed, not encrypted
-- -------------------------------------
-- The platform never needs to *read* a CI token back — it only has to
-- verify one. That makes a SHA-256 hash the right tool: the lookup is
-- a deterministic index hit instead of a decrypt-and-compare over every
-- workspace, and a stolen database row yields no usable credential.
--
-- The tokens elsewhere in this schema that ARE retrievable (the GitHub
-- OAuth access token, the AI provider key) are encrypted, because the
-- platform has to present them to a third party. A CI token is never
-- presented anywhere but LivingDocs, so hashing it is both simpler and
-- safer.
--
-- One row per workspace: rotating replaces the hash in place, which
-- immediately invalidates the secret currently held by GitHub.

CREATE TABLE workspace_ci_tokens (
    workspace_id  UUID        PRIMARY KEY,
    -- SHA-256 of the token, hex-encoded. The plaintext is shown to the
    -- manager exactly once, at creation time, and is never stored.
    token_hash    VARCHAR(64) NOT NULL,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- When the token was last presented by a CI job. Purely diagnostic.
    last_used_at  TIMESTAMPTZ,

    CONSTRAINT fk_workspace_ci_tokens_ws
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

-- The CI request path hashes the presented token and looks it up here.
CREATE INDEX idx_workspace_ci_tokens_hash
    ON workspace_ci_tokens (token_hash);
