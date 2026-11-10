-- LivingDocs V53 — the DRIFT_RESOLVE permission.
--
-- Problem this migration solves
-- ---------------------------
-- Resolving a drift alert was guarded by workspace membership alone, so
-- every member of a workspace could accept, dismiss or mark fixed any
-- drift alert in it — including a CRITICAL one. That is a content
-- judgement ("is the documentation still correct, or is the analysis
-- wrong?"), and it belongs to the people who review content, not to
-- every author.
--
-- The asymmetry with the detection side is deliberate:
--
--   * DRIFT_DETECT_RUN — triggering a scan is a machine action; any
--     member may start one, and the pull-request path does it on the
--     member's behalf.
--   * DRIFT_RESOLVE    — closing an alert is a judgement about the
--     documentation; it stays with the review tiers.
--
-- What this migration does
-- -----------------------
-- 1) Registers the DRIFT_RESOLVE permission code.
-- 2) Grants it to the roles that legitimately need it:
--      STAFF          — reviews content; it is the tier that judges
--                       whether a drifted page is still correct.
--      TECHNICAL_LEAD — owns the technical accuracy of the docs.
--      MANAGER        — owns the workspace; reads everything its
--                       technical lead reads.
--      ADMIN          — everything (granted by the cross join below).
-- 3) Asserts explicitly that DEVELOPER does NOT hold it. An operator
--    may have edited the matrix through the admin UI between V36 and
--    this migration, so the absence is enforced rather than assumed.

-- ----------------------------------------------------------------------------
-- 1) New permission code.
--    The category is "insight", next to DRIFT_DETECT_RUN.
-- ----------------------------------------------------------------------------
INSERT INTO permissions (id, code, name, description, category, display_order) VALUES
(gen_random_uuid(), 'DRIFT_RESOLVE', 'Resolve drift alert',
 'Accept, dismiss or mark fixed a documentation drift alert.', 'insight', 40)
ON CONFLICT (code) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2) Grants.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('STAFF', 'TECHNICAL_LEAD', 'MANAGER')
  AND p.code = 'DRIFT_RESOLVE'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );

-- ADMIN receives every permission.
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code = 'DRIFT_RESOLVE'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );

-- ----------------------------------------------------------------------------
-- 3) DEVELOPER must not resolve drift alerts.
--
--    Asserted explicitly rather than only omitted above: an admin may
--    have edited the matrix through the admin UI between V36 and this
--    migration, and resolving an alert is a review-tier decision.
-- ----------------------------------------------------------------------------
DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'DEVELOPER'
  AND p.code = 'DRIFT_RESOLVE';
