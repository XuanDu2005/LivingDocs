-- LivingDocs V50 — the dependency graph permission.
--
-- Problem this migration solves
-- ---------------------------
-- Task P2-6 adds the documentation-to-code dependency graph and the
-- impact analysis ("what breaks when I change this method?").
--
-- The endpoint could have been gated behind HEALTH_DASHBOARD_VIEW, but
-- that conflates two different questions:
--
--   * HEALTH_DASHBOARD_VIEW → "how healthy is the documentation of this
--     workspace?" (counts, freshness, drift totals)
--   * DEPENDENCY_GRAPH_VIEW → "what does this documentation depend on,
--     and which documentation does this code change invalidate?"
--
-- The second question exposes the *structure* of the workspace: which
-- document documents which class, which module a file belongs to, which
-- pull request is open on the repository. That is more than a health
-- metric, and it deserves its own capability so an administrator can
-- hand it out separately.
--
-- What this migration does
-- -----------------------
-- 1) Registers the DEPENDENCY_GRAPH_VIEW permission code.
-- 2) Grants it to the roles that legitimately need it:
--      TECHNICAL_LEAD — owns the technical structure of the codebase;
--                       this is the tier the requirement names.
--      MANAGER        — owns the workspace; reads everything its
--                       technical lead reads.
--      STAFF          — reviews content against the code; it needs to
--                       know which documentation a code change touches
--                       to do that review well.
--      ADMIN          — everything (granted by the cross join below).
--
-- DEVELOPER deliberately does NOT get it: the graph is an oversight
-- surface, and a developer who links a code entity to a document
-- already sees both sides of that one edge in the "Links" tab. This
-- mirrors how V36 removed the oversight grants from DEVELOPER.

-- ----------------------------------------------------------------------------
-- 1) New permission code.
--    The category is "insight", next to HEALTH_DASHBOARD_VIEW and
--    ANALYTICS_VIEW.
-- ----------------------------------------------------------------------------
INSERT INTO permissions (id, code, name, description, category, display_order) VALUES
(gen_random_uuid(), 'DEPENDENCY_GRAPH_VIEW', 'View dependency graph',
 'Read the documentation-to-code dependency graph and the impact analysis of a code entity.', 'insight', 25)
ON CONFLICT (code) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2) Grants.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('TECHNICAL_LEAD', 'MANAGER', 'STAFF')
  AND p.code = 'DEPENDENCY_GRAPH_VIEW'
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
  AND p.code = 'DEPENDENCY_GRAPH_VIEW'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );

-- ----------------------------------------------------------------------------
-- 3) DEVELOPER must not read the graph.
--
--    Asserted explicitly rather than only omitted above: an admin may
--    have edited the matrix through the admin UI between V36 and this
--    migration, and the graph exposes the structure of the workspace.
-- ----------------------------------------------------------------------------
DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'DEVELOPER'
  AND p.code = 'DEPENDENCY_GRAPH_VIEW';
