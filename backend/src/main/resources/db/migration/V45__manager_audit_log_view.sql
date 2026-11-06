-- LivingDocs V45 — the MANAGER oversight tier of the audit trail.
--
-- Problem this migration solves
-- ---------------------------
-- The MANAGER role is required to "view the complete audit trail of a
-- document (AI-generated, auto-updated, staff-edited) before granting
-- approval", and to open the workspace-wide audit log. The permission
-- that gates both surfaces is AUDIT_LOG_VIEW.
--
-- V36 seeded the capability matrix and granted AUDIT_LOG_VIEW to
-- ADMIN (everything) and to TECHNICAL_LEAD — while its own comment
-- says the trail "stays with MANAGER/ADMIN". The comment and the seed
-- contradicted each other, and the seed won: the MANAGER tier had no
-- grant, so a Manager got 403 on both audit surfaces. The role model
-- has always described MANAGER as owning the workspace with "full
-- oversight", and the matrix did not express that.
--
-- V42 (the per-document audit trail) already re-asserted the grant.
-- This migration states the invariant in its own right, for two
-- reasons:
--
--   1. It is the authoritative statement of the oversight tier of the
--      audit trail. The grant belongs to the role model, not to the
--      feature that happened to need it — burying it in the audit
--      migration made it invisible to anyone reading the matrix.
--
--   2. It also asserts the *negative* half of the invariant. The seed
--      migrations grant capabilities to whole tiers with a cross join,
--      so a capability that several roles share can easily end up on a
--      tier that must not have it. AUDIT_LOG_VIEW is deliberately not
--      one of those: the trail says who did what in the workspace, and
--      that is the oversight surface of the Manager tier, not the
--      review surface of Staff (a Staff reviewer reads the *reviews*
--      of a version, not the governance trail of the workspace) and
--      certainly not of the Developer tier (which contributes).
--
--      TECHNICAL_LEAD keeps the grant: it owns the technical
--      configuration of the workspace, and the trail is how that
--      configuration is audited. That grant is intentional and is
--      left untouched here.
--
-- Everything here is idempotent: re-running the migration is a no-op.

-- ----------------------------------------------------------------------------
-- 1. The MANAGER tier reads the trail.
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

-- ----------------------------------------------------------------------------
-- 2. The Staff and Developer tiers do not.
-- ----------------------------------------------------------------------------
-- STAFF reviews content against the governance policy, which is what
-- GOVERNANCE_VIEW is for. It has no business reading who changed which
-- workspace setting, or who published which repository — that is the
-- Manager's oversight surface.
--
-- DEVELOPER contributes documentation. It holds no oversight capability
-- at all (V36 revokes the whole oversight set from it); this delete
-- asserts the invariant for the one code that V36 does not mention.
DELETE FROM role_permissions rp
  USING roles r, permissions p
 WHERE rp.role_id = r.id
   AND rp.permission_id = p.id
   AND r.code IN ('STAFF', 'DEVELOPER')
   AND p.code = 'AUDIT_LOG_VIEW';
