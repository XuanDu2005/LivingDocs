-- LivingDocs V46 — supersede-able review verdicts + review delegation.
--
-- Problem this migration solves
-- ---------------------------
-- Two things the review workflow could not express in the schema:
--
--   1. A verdict was append-only in practice but not in the database.
--      The partial unique index added in V40 allows exactly one *open*
--      verdict row per (version, tier). A reviewer who approved a
--      version and then noticed a mistake could not record the
--      correction: inserting a second verdict row hit the index and
--      raised a constraint violation, which the global exception
--      handler turned into a 500. Fixing a verdict is a completely
--      ordinary action — a reviewer mis-clicking "Reject" must be
--      able to correct it — but the request crashed instead.
--
--      The fix is to make the verdict rows *supersedable* rather than
--      deletable: the old row stays (the trail is append-only), it
--      gets a `superseded_at` stamp, and the partial index is narrowed
--      to the rows that are still current. The review history still
--      shows the original verdict, and the "latest verdict wins" rule
--      keeps working because it only looks at rows that are current.
--
--   2. There was no way to hand a review or an approval to somebody
--      else. The MANAGER tier could approve a document, but a Manager
--      who is on leave — or one who simply wants a second pair of eyes
--      — had no mechanism to route the work. Requirement #12 of the
--      MANAGER role ("delegate the review/approval to another Manager")
--      had no backend at all.
--
--      The delegation is per *workspace*: a delegate receives the
--      review/approval work of the whole workspace for a bounded
--      period. Narrowing it per document would be a second mechanism
--      for the same thing, and the workspace scope is the one the role
--      model describes.
--
-- Everything here is idempotent: re-running the migration is a no-op.

-- ----------------------------------------------------------------------------
-- 1. Supersedable verdicts.
-- ----------------------------------------------------------------------------
-- `superseded_at` is NULL for a verdict that is still current. A
-- non-NULL value means "this verdict was replaced by a later one in
-- the same tier" — the row is kept for the trail, but it no longer
-- counts as the authoritative opinion.
ALTER TABLE document_reviews
    ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_document_reviews_superseded
    ON document_reviews (document_version_id, superseded_at);

-- The V40 index has to be dropped before it can be replaced: its
-- predicate (`decision IN (...)`) no longer distinguishes a current
-- verdict from a superseded one, so it would keep blocking the very
-- corrections this migration enables.
DROP INDEX IF EXISTS uq_document_reviews_open_per_tier;

-- The new index covers the same invariant — one current verdict per
-- (version, tier) — but only over the rows that are still current.
-- A COMMENT verdict is still excluded: it carries no opinion about
-- the content, so two comments in the same tier are not a conflict.
CREATE UNIQUE INDEX IF NOT EXISTS uq_document_reviews_current_per_tier
    ON document_reviews (document_version_id, reviewer_role)
    WHERE superseded_at IS NULL
      AND decision IN ('APPROVED', 'REJECTED', 'REQUEST_CHANGES');

-- ----------------------------------------------------------------------------
-- 2. Review delegation.
-- ----------------------------------------------------------------------------
-- One row is one delegation: `from_user_id` hands the review/approval
-- work of `workspace_id` to `to_user_id` for the given tier.
--
-- The tier is stored explicitly instead of being derived from the
-- delegate's permissions, because a delegation has to state *what*
-- is being delegated. A Staff delegation and a Manager delegation are
-- different acts with different consequences.
CREATE TABLE IF NOT EXISTS review_delegations (
    id              UUID        PRIMARY KEY,
    workspace_id    UUID        NOT NULL,
    from_user_id    UUID        NOT NULL,
    to_user_id      UUID        NOT NULL,
    tier            VARCHAR(16)  NOT NULL,
    reason          VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID,

    CONSTRAINT chk_review_delegations_tier
        CHECK (tier IN ('STAFF', 'MANAGER')),
    CONSTRAINT fk_review_delegations_ws
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_delegations_from
        FOREIGN KEY (from_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_delegations_to
        FOREIGN KEY (to_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_delegations_revoked_by
        FOREIGN KEY (revoked_by) REFERENCES users (id) ON DELETE SET NULL
);

-- The lookup path is "which delegations are active in this workspace",
-- so the index is keyed by the workspace and the revocation stamp.
CREATE INDEX IF NOT EXISTS idx_review_delegations_workspace
    ON review_delegations (workspace_id, revoked_at);

CREATE INDEX IF NOT EXISTS idx_review_delegations_to
    ON review_delegations (to_user_id, revoked_at);

-- At most one *active* delegation per (workspace, delegate, tier).
--
-- This has to be a partial index rather than a table constraint: the
-- table keeps revoked rows for the trail, and a plain UNIQUE would
-- then forbid delegating to the same person again after a revocation —
-- the second delegation would collide with the revoked row and the
-- request would fail with a constraint violation. Only the rows that
-- are still in force participate.
CREATE UNIQUE INDEX IF NOT EXISTS uq_review_delegations_active
    ON review_delegations (workspace_id, to_user_id, tier)
    WHERE revoked_at IS NULL;

-- ----------------------------------------------------------------------------
-- 3. The permission that gates the delegation surface.
-- ----------------------------------------------------------------------------
-- Delegating is a Manager-tier act: it hands over part of the
-- oversight work of the workspace. It is a separate capability from
-- DOCUMENT_VERSION_APPROVE because approving and *redistributing* the
-- approval work are different decisions, and an administrator may
-- reasonably want to allow one without the other.
--
-- The grant-only insert follows the rule of every other seed
-- migration in this repo: it fills in what the role model says and
-- never revokes what an administrator deliberately tightened.
INSERT INTO permissions (id, code, name, description, category, display_order)
SELECT gen_random_uuid(),
       'REVIEW_DELEGATION_MANAGE',
       'Manage review delegation',
       'Delegate review and approval work to another member of the workspace',
       'review',
       60
WHERE NOT EXISTS (
    SELECT 1 FROM permissions WHERE code = 'REVIEW_DELEGATION_MANAGE'
);

INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
  FROM roles r
    CROSS JOIN permissions p
 WHERE r.code IN ('MANAGER', 'ADMIN')
   AND p.code = 'REVIEW_DELEGATION_MANAGE'
   AND NOT EXISTS (
       SELECT 1 FROM role_permissions rp
        WHERE rp.role_id = r.id
          AND rp.permission_id = p.id
   );
