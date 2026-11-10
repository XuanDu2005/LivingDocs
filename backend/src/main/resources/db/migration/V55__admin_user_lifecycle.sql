-- LivingDocs V55 — Administrator user lifecycle.
--
-- Adds the column that lets a platform administrator create an account
-- with a *temporary* password and force the account owner to replace it
-- before the account can be used for anything else.
--
-- The flag is deliberately on the account rather than in a token: the
-- JWT authentication filter already loads the user row on every
-- request, so the check costs nothing extra and cannot go stale the way
-- a token claim would.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.must_change_password IS
    'TRUE when an administrator issued a temporary password; the owner must replace it before using the account.';
