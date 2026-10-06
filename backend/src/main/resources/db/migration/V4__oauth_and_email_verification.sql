-- LivingDocs V4 — OAuth (Google, GitHub) + email verification + password reset.
--
-- Adds:
--   * users.email_verified / users.email_verified_at
--   * user_oauth_identities — one row per linked (provider, providerUserId)
--   * email_verification_codes — short-lived 6-digit OTP for REGISTER / EMAIL_CHANGE
--   * password_reset_codes — short-lived token for "forgot password" flow
--   * oauth_states — CSRF state for OAuth start/callback round-trips
--
-- All new tables cascade-delete with users so account deletion stays clean.

-- ----------------------------------------------------------------------------
-- 1. Email verification status on users
-- ----------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN email_verified     BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified_at  TIMESTAMPTZ;

-- Backfill: accounts created before this migration are treated as already
-- verified (otherwise every existing user would be locked out).
UPDATE users
   SET email_verified    = TRUE,
       email_verified_at = NOW()
 WHERE email_verified = FALSE;

-- ----------------------------------------------------------------------------
-- 2. Linked OAuth identities (one user can have many)
-- ----------------------------------------------------------------------------

CREATE TABLE user_oauth_identities (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL,
    provider          VARCHAR(16)  NOT NULL,                       -- 'google' | 'github'
    provider_user_id  VARCHAR(128) NOT NULL,                       -- 'sub' (Google) or numeric id (GitHub)
    provider_email    VARCHAR(255),                                -- cached email from provider
    display_name      VARCHAR(255),                                -- cached display name
    avatar_url        VARCHAR(500),                                -- cached avatar
    access_token      VARCHAR(500),                                -- encrypted TODO in a follow-up
    refresh_token     VARCHAR(500),
    token_expires_at  TIMESTAMPTZ,
    linked_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at      TIMESTAMPTZ,
    CONSTRAINT uq_oauth_provider        UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_oauth_user            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_oauth_provider_values CHECK (provider IN ('google', 'github'))
);

CREATE INDEX idx_oauth_user      ON user_oauth_identities (user_id);
CREATE INDEX idx_oauth_provider  ON user_oauth_identities (provider, provider_user_id);

-- ----------------------------------------------------------------------------
-- 3. Email verification OTP (6-digit code, 15-minute TTL)
-- ----------------------------------------------------------------------------

CREATE TABLE email_verification_codes (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL,
    code_hash    VARCHAR(255) NOT NULL,                            -- BCrypt of the 6-digit code
    purpose      VARCHAR(32)  NOT NULL,                            -- 'REGISTER' | 'EMAIL_CHANGE'
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_evc_user           FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_evc_purpose        CHECK (purpose IN ('REGISTER', 'EMAIL_CHANGE'))
);

CREATE INDEX idx_evc_user_purpose  ON email_verification_codes (user_id, purpose, expires_at);

-- ----------------------------------------------------------------------------
-- 4. Password reset tokens (longer OTP, 30-minute TTL)
-- ----------------------------------------------------------------------------

CREATE TABLE password_reset_codes (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL,
    code_hash    VARCHAR(255) NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_prc_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_prc_user_expires ON password_reset_codes (user_id, expires_at);

-- ----------------------------------------------------------------------------
-- 5. OAuth state (CSRF protection, 10-minute TTL)
-- ----------------------------------------------------------------------------

CREATE TABLE oauth_states (
    state_key       VARCHAR(64)  PRIMARY KEY,                       -- random 32-byte hex
    user_id         UUID,                                            -- null = login/register, set = LINK
    action          VARCHAR(16)  NOT NULL,                           -- 'LOGIN' | 'REGISTER' | 'LINK'
    provider        VARCHAR(16)  NOT NULL,                           -- 'google' | 'github'
    redirect_after  VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,                           -- 10 min
    CONSTRAINT ck_oauth_state_action   CHECK (action   IN ('LOGIN', 'REGISTER', 'LINK')),
    CONSTRAINT ck_oauth_state_provider CHECK (provider IN ('google', 'github'))
);

CREATE INDEX idx_oauth_state_expires ON oauth_states (expires_at);
