-- Add a column to remember the GitHub webhook id we registered for each
-- connected repository, so we can clean it up on disconnect. Nullable
-- because (a) older rows predate this migration and (b) the sandbox
-- client is allowed to keep a non-null fake id but a failed real
-- registration should still leave the repo connected.
ALTER TABLE repositories
    ADD COLUMN webhook_id BIGINT NULL;

-- Optional diagnostic: nothing else needs an index here because
-- cleanup looks rows up by primary key.
