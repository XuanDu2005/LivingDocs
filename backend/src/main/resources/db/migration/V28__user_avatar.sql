-- LivingDocs V28 — User avatar (profile picture).
--
-- Stores the *relative* storage key of the uploaded avatar image
-- (e.g. "avatars/<user-id>/<uuid>.png"). The public URL is derived
-- from this key by the backend so that the host/port can change
-- without rewriting every row.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500);

-- Comment for future readers: NULL means "the user has not uploaded
-- a picture" and the UI falls back to initials.
