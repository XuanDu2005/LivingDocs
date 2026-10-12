-- LivingDocs V22 — Per-repository auto-update toggle.
--
-- Backed by Repository.autoUpdateEnabled. Defaults to TRUE so the
-- behaviour matches the old per-document setting; users can opt a
-- repository out via the Repository page.

ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS auto_update_enabled BOOLEAN NOT NULL DEFAULT TRUE;
