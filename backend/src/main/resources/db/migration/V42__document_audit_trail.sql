-- LivingDocs V42 — the per-document audit trail.
--
-- Problem this migration solves
-- ---------------------------
-- The MANAGER role is required to "view the complete audit trail of a
-- document (AI-generated, auto-updated, staff-edited) before granting
-- approval", but the system only exposed one audit endpoint:
-- GET /workspaces/{id}/audit-logs — the workspace-wide trail.
--
-- That endpoint cannot answer the question:
--
--   * it is paginated and mixes in every other action of the workspace,
--     so the rows that concern one document are scattered across
--     unrelated pages;
--   * it carries no link to the document at all — the association only
--     existed inside the JSON payload, which cannot be indexed, so a
--     per-document query was not even expressible;
--   * a reader who wanted "what happened to this document" had to page
--     through the whole workspace and filter by hand.
--
-- Two things are fixed here.
--
-- 1) audit_logs gains a document_id column. The trail of a document
--    becomes a real, indexed relation instead of a JSON lookup. The
--    existing rows are backfilled from the payload, which already
--    carried the documentId for every document-scoped action.
--
-- 2) MANAGER is granted AUDIT_LOG_VIEW. V36 gave the permission to
--    ADMIN and TECHNICAL_LEAD only, even though the very same
--    migration describes MANAGER as owning "full oversight". Without
--    the grant the Manager tier could not read the trail it is
--    required to review before granting approval.
--
-- No foreign key is declared on document_id on purpose. The audit log
-- is append-only and has to survive the deletion of the document it
-- describes; a cascading foreign key would either delete the trail
-- rows or silently rewrite them. A dangling document_id is harmless —
-- the workspace-wide trail still shows the rows.

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS document_id UUID;

-- The trail is read as "everything that happened to this document,
-- newest first", so the index follows that access pattern.
CREATE INDEX IF NOT EXISTS idx_audit_logs_document
    ON audit_logs (document_id, created_at DESC);

-- ----------------------------------------------------------------------------
-- Backfill.
--
-- Every document-scoped action already carried the document id inside
-- its JSON payload ("documentId"). The CASE expression is used instead
-- of a plain cast so a malformed value can never abort the migration:
-- the cast is only evaluated when the regex matched.
-- ----------------------------------------------------------------------------
UPDATE audit_logs
   SET document_id = CASE
           WHEN payload->>'documentId' ~
                '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
           THEN (payload->>'documentId')::uuid
           ELSE NULL
       END
 WHERE document_id IS NULL
   AND resource_type IN ('document', 'document_version')
   AND payload ? 'documentId';

-- ----------------------------------------------------------------------------
-- The MANAGER tier reads the trail of a document before approving it.
--
-- V36 gave AUDIT_LOG_VIEW to ADMIN (everything) and to TECHNICAL_LEAD
-- (the tier that owns the technical configuration), but left MANAGER
-- out — even though MANAGER is the tier the two-tier workflow ends at.
-- The grant is idempotent.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
  FROM roles r
  CROSS JOIN permissions p
 WHERE r.code = 'MANAGER'
   AND p.code = 'AUDIT_LOG_VIEW'
   AND NOT EXISTS (
       SELECT 1 FROM role_permissions rp
        WHERE rp.role_id = r.id
          AND rp.permission_id = p.id
   );
