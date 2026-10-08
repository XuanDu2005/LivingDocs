-- LivingDocs V9 — Template generation settings.
--
-- Phase 2e: extend the doc_templates table with metadata that lets an
-- administrator choose the desired output format (markdown today; HTML
-- or PDF planned) and the auto-generation triggers that determine
-- when a template should be run automatically by the GitHub / GitLab
-- webhook flow.
--
-- Default values preserve the existing behaviour so the migration is
-- non-breaking.

ALTER TABLE doc_templates
    ADD COLUMN output_format VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN';

ALTER TABLE doc_templates
    ADD COLUMN auto_generate_on_commit BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE doc_templates
    ADD COLUMN auto_generate_on_pr BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE doc_templates
    ADD COLUMN auto_generate_on_merge BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index for the most common access path: "which templates should
-- we run when a PR lands on workspace X?". The partial predicate
-- keeps the index small even when most rows are markdown-with-no-triggers.
CREATE INDEX idx_doc_templates_auto_pr
    ON doc_templates (doc_type)
    WHERE auto_generate_on_pr;