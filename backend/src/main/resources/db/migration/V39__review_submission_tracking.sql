-- LivingDocs V39 — track who submitted a document version for review.
--
-- Problem this migration solves
-- ---------------------------
-- The review workflow has two very different audiences:
--
--   * the reviewers, who act on the versions sitting in the Staff /
--     Manager queues, and
--   * the authors, who need to see what happened to the version they
--     pushed into the queue.
--
-- Before this migration there was no way to answer "which versions did
-- *I* submit?", because document_versions only recorded the author of
-- the change (actor_user_id) and never the person who submitted it for
-- review. A DEVELOPER — whose whole job is to author drafts and hand
-- them over — therefore had no screen to look at: the review queue page
-- showed three tabs (Staff / Manager / Publish) that are all gated
-- behind roles a developer does not hold, so it rendered empty.
--
-- What this migration does
-- ------------------------
-- 1) Adds two columns to document_versions:
--      * submitted_by_user_id — the user who pushed the version into the
--        review workflow.
--      * submitted_at        — when that happened.
-- 2) Indexes them so the "my submissions" lookup stays cheap.
--
-- Both columns are nullable: a version that was never submitted (a pure
-- draft) keeps them NULL, and a version whose submitter was deleted
-- keeps the row but loses the pointer (ON DELETE SET NULL).
ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS submitted_by_user_id UUID,
    ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_doc_versions_submitter'
    ) THEN
        ALTER TABLE document_versions
            ADD CONSTRAINT fk_doc_versions_submitter
            FOREIGN KEY (submitted_by_user_id) REFERENCES users (id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_document_versions_submitter
    ON document_versions (submitted_by_user_id, submitted_at DESC);

-- Backfill: versions that already went through the workflow were
-- submitted by their author. The heuristic is deliberately conservative —
-- a wrong pointer would show a version under the wrong author, so only
-- rows whose author is known and whose status proves they entered the
-- review flow are touched.
UPDATE document_versions v
SET submitted_by_user_id = v.actor_user_id,
    submitted_at = v.created_at
WHERE v.submitted_by_user_id IS NULL
  AND v.actor_user_id IS NOT NULL
  AND v.status IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'REJECTED');
