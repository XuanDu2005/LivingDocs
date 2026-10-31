-- LivingDocs V35 — separate "draft" versions from "submitted for review".
--
-- Until now a freshly created version was born with status PENDING, which
-- is also the status the review queue filters on. Every AI generation,
-- every manual edit and every rollback therefore landed in the Staff
-- review queue automatically, even when the author never asked for it.
--
-- DRAFT makes the intent explicit: a version starts as a draft and only
-- enters the queue when its author submits it.
--
-- The migration moves the existing PENDING rows to DRAFT, because those
-- rows were created by that automatic push. Everything already in the
-- queue at the moment of the upgrade is therefore still visible to
-- reviewers, and no version disappears from a reviewer's list.
--
-- Rows that already went through review (IN_REVIEW, APPROVED, PUBLISHED,
-- REJECTED, SUPERSEDED) keep their status.
UPDATE document_versions
SET status = 'DRAFT'
WHERE status = 'PENDING';

-- The document's own status mirrors the workflow. A document whose only
-- versions are drafts is no longer "in review" — nothing is waiting on
-- a reviewer for it.
UPDATE documents d
SET status = 'DRAFT'
WHERE d.status = 'IN_REVIEW'
  AND NOT EXISTS (
      SELECT 1 FROM document_versions v
      WHERE v.document_id = d.id
        AND v.status IN ('PENDING', 'IN_REVIEW', 'APPROVED')
  );
