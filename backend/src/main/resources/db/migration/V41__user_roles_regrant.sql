-- ----------------------------------------------------------------------------
-- Re-granting a revoked platform role.
-- ----------------------------------------------------------------------------
-- `user_roles` carried a full-table UNIQUE (user_id, role_id) constraint
-- while the table is a soft-delete log: a revoked assignment keeps its
-- row so the audit trail can show every role a user ever held.
--
-- The two designs contradict each other. Granting a role that was
-- revoked earlier inserts a second row for the same (user_id, role_id)
-- pair, and the constraint rejects it with
--
--   ERROR: duplicate key value violates unique constraint "uq_user_roles"
--
-- which surfaced as an HTTP 500 on `PUT /api/v1/admin/users/{id}/roles`.
-- The practical effect was that an administrator could revoke a role
-- but could never grant it back — and, more damagingly, promoting an
-- account from DEVELOPER to STAFF failed outright whenever the
-- account had held STAFF at some point in the past.
--
-- The constraint becomes a *partial* unique index over the active rows
-- only. Revoked rows may repeat freely (that is the whole point of the
-- audit trail), but a user can never hold the same role twice at the
-- same time.
--
-- The old constraint is dropped first. If it does not exist — a database
-- that predates V6, or one where it was already dropped — the IF EXISTS
-- keeps the migration running.
--
-- No rows have to be de-duplicated: the original constraint already
-- guaranteed that (user_id, role_id) was unique across the whole table,
-- so the data satisfies the stricter condition the partial index
-- enforces.

ALTER TABLE user_roles
    DROP CONSTRAINT IF EXISTS uq_user_roles;

-- One active assignment per (user, role). Revoked rows are outside the
-- index, so re-granting a role later is allowed.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_roles_active
    ON user_roles (user_id, role_id)
    WHERE revoked_at IS NULL;

-- The lookup used by the permission resolver filters on the active rows
-- anyway; this index keeps that scan cheap now that the table holds
-- the full history.
CREATE INDEX IF NOT EXISTS idx_user_roles_user_active
    ON user_roles (user_id)
    WHERE revoked_at IS NULL;
