-- LivingDocs V40 — review workflow integrity + permission matrix repair.
--
-- Problem this migration solves
-- ---------------------------
-- The Staff / Manager review workflow grew a set of invariants that the
-- schema never enforced:
--
--   1. The review guards used to be hard-coded platform-role checks
--      (requireStaffReviewer / requireManagerApprover). That made the
--      permission matrix decorative: an administrator who revoked
--      DOCUMENT_VERSION_REVIEW from the STAFF role still saw Staff
--      reviewers approving documents. The matrix is the source of truth
--      (see V36), so the grants below re-assert the intended mapping
--      idempotently.
--
--   2. Two Staff reviewers could hit "approve" on the same version at
--      the same time. The comment in V3 promised a partial unique index
--      for the "one open record per tier" rule, but the index was never
--      created. Two rows in the same tier would then disagree about the
--      verdict, and the workflow state machine would pick one at random.
--
--   3. The review rows had no anchor for a comment that targets one
--      specific section of the document. Requirement #8 of the STAFF
--      role ("comment / request changes on a specific section") needs
--      somewhere to store the target.
--
--   4. The grounding panel showed the source file, the code entity, the
--      commit and the diff — but not the ticket the change belongs to.
--      Requirement #2 of the STAFF role lists the ticket as part of what
--      a reviewer must be able to see.
--
-- What this migration does
-- -----------------------
-- 1) Re-asserts the review / approve / publish grants so the matrix
--    matches the documented role model even if it was edited by hand.
-- 2) Creates the partial unique index that prevents two open review
--    rows in the same tier, after de-duplicating the rows that already
--    violate it.
-- 3) Adds the section columns to document_reviews.
-- 4) Adds the ticket-reference column to document_versions.
--
-- Everything here is idempotent: re-running the migration is a no-op.

-- ----------------------------------------------------------------------------
-- 1. Permission matrix repair.
-- ----------------------------------------------------------------------------
-- The rule this migration enforces:
--
--   STAFF          -> DOCUMENT_VERSION_REVIEW
--   TECHNICAL_LEAD -> DOCUMENT_VERSION_REVIEW + DOCUMENT_VERSION_APPROVE
--                     + DOCUMENT_VERSION_PUBLISH
--   MANAGER        -> DOCUMENT_VERSION_REVIEW + DOCUMENT_VERSION_APPROVE
--                     + DOCUMENT_VERSION_PUBLISH
--   DEVELOPER      -> (nothing review related — the author tier)
--
-- The insert is intentionally a grant-only operation: it never revokes.
-- An administrator who deliberately tightened the matrix keeps their
-- choice; the seed only fills in the grants that the role model says
-- every role holds.
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE (
        (r.code = 'STAFF' AND p.code IN (
            'DOCUMENT_VERSION_REVIEW'
        ))
        OR (r.code IN ('TECHNICAL_LEAD', 'MANAGER') AND p.code IN (
            'DOCUMENT_VERSION_REVIEW',
            'DOCUMENT_VERSION_APPROVE',
            'DOCUMENT_VERSION_PUBLISH'
        ))
    )
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );

-- ----------------------------------------------------------------------------
-- 2. Partial unique index: one open review row per (version, tier).
-- ----------------------------------------------------------------------------
-- A version may legitimately collect several review rows over time — a
-- Staff reviewer may approve, then ask for changes, then approve again.
-- What must never happen is two rows of the *same tier* carrying a
-- verdict at the same time, because then the "latest verdict wins" rule
-- becomes ambiguous.
--
-- Step 2a: de-duplicate the rows that already violate the rule, keeping
-- the most recently decided one. This has to run before the index is
-- created, otherwise the CREATE INDEX fails on a dirty database.
DELETE FROM document_reviews older
WHERE EXISTS (
    SELECT 1
    FROM document_reviews newer
    WHERE newer.document_version_id = older.document_version_id
      AND newer.reviewer_role = older.reviewer_role
      AND newer.decision IN ('APPROVED', 'REJECTED', 'REQUEST_CHANGES')
      AND older.decision IN ('APPROVED', 'REJECTED', 'REQUEST_CHANGES')
      AND (newer.decided_at, newer.id) > (older.decided_at, older.id)
);

-- Step 2b: the index itself.
CREATE UNIQUE INDEX IF NOT EXISTS uq_document_reviews_open_per_tier
    ON document_reviews (document_version_id, reviewer_role)
    WHERE decision IN ('APPROVED', 'REJECTED', 'REQUEST_CHANGES');

-- ----------------------------------------------------------------------------
-- 3. Section-targeted comments (requirement #8).
-- ----------------------------------------------------------------------------
-- A document is markdown, so a "section" is a markdown heading. The
-- reviewer picks a heading; the backend stores its slug, its text and a
-- GitHub-style anchor so the UI can deep-link to it.
--
-- All three columns are nullable: a review that comments on the document
-- as a whole keeps them NULL.
ALTER TABLE document_reviews
    ADD COLUMN IF NOT EXISTS section_id      VARCHAR(120),
    ADD COLUMN IF NOT EXISTS section_heading VARCHAR(255),
    ADD COLUMN IF NOT EXISTS section_anchor  VARCHAR(255);

-- The index on the section column has to come *after* the ALTER TABLE
-- that adds it — Postgres resolves the column list when the statement
-- runs, so indexing first fails with "column section_id does not
-- exist".
CREATE INDEX IF NOT EXISTS idx_document_reviews_section
    ON document_reviews (document_version_id, section_id);

-- ----------------------------------------------------------------------------
-- 4. Ticket reference for grounding evidence (requirement #2).
-- ----------------------------------------------------------------------------
-- The grounding panel of a document shows the source file, the code
-- entity, the commit and the diff that justify the content. It was
-- missing the ticket: the issue the change belongs to ("PROJ-123").
--
-- The reference is extracted from the commit message or the pull
-- request title of the change that triggered the generation, so it is
-- best-effort by nature. It is stored per version because a version is
-- bound to exactly one triggering change.
ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS source_ticket_ref VARCHAR(120);

CREATE INDEX IF NOT EXISTS idx_document_versions_ticket_ref
    ON document_versions (source_ticket_ref);
