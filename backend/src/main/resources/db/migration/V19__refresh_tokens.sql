-- LivingDocs V19 — Refresh tokens for OAuth2-style session rotation.
--
-- The /auth/login and /auth/oauth/callback endpoints will now also issue a
-- long-lived refresh token. The raw token is never stored; only its
-- SHA-256 hash. Tokens are single-use: rotation deletes the old row and
-- creates a new one.
-- Idempotent.

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL,
    token_hash      VARCHAR(128) NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    user_agent      VARCHAR(256),
    ip_address      VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user       ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
