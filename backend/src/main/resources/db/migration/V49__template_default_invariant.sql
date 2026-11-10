-- LivingDocs V49 — "At most one default template per (workspace, docType)".
--
-- DocTemplateService.resolveDefault() used to look the default up with
-- findFirstByWorkspaceIdAndDocTypeAndIsDefaultTrue(...). If two templates
-- of the same doc type both carried is_default = TRUE, the row that came
-- back was arbitrary — the resolution silently depended on the physical
-- row order.
--
-- This migration
--   1. cleans up the rows that already violate the invariant,
--   2. adds two partial unique indexes that make the violation
--      impossible from now on.
--
-- Two indexes are needed because the scope key is different for the two
-- kinds of templates:
--   * workspace templates are scoped by (workspace_id, doc_type),
--   * global templates have no workspace, so they are scoped by doc_type
--     alone.
--
-- The indexes are partial (WHERE is_default = TRUE) so only the default
-- rows have to be unique — the vast majority of the rows are unaffected.

-- ----------------------------------------------------------------------------
-- 1) Clean up pre-existing violations.
-- ----------------------------------------------------------------------------
--
-- Workspace scope: keep the most recently updated default per
-- (workspace_id, doc_type) and demote the rest.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY workspace_id, doc_type
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM doc_templates
    WHERE is_default = TRUE
      AND workspace_id IS NOT NULL
)
UPDATE doc_templates d
SET is_default = FALSE,
    updated_at = NOW()
FROM ranked r
WHERE d.id = r.id
  AND r.rn > 1;

-- Global scope: keep the most recently updated default per doc_type and
-- demote the rest.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY doc_type
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM doc_templates
    WHERE is_default = TRUE
      AND workspace_id IS NULL
)
UPDATE doc_templates d
SET is_default = FALSE,
    updated_at = NOW()
FROM ranked r
WHERE d.id = r.id
  AND r.rn > 1;

-- ----------------------------------------------------------------------------
-- 2) Enforce the invariant.
-- ----------------------------------------------------------------------------

CREATE UNIQUE INDEX IF NOT EXISTS uq_doc_templates_workspace_default
    ON doc_templates (workspace_id, doc_type)
    WHERE is_default = TRUE AND workspace_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_doc_templates_global_default
    ON doc_templates (doc_type)
    WHERE is_default = TRUE AND workspace_id IS NULL;
