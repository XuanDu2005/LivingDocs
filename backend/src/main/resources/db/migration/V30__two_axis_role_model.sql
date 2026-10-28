-- LivingDocs V30 — Two-axis role model cleanup.
--
-- Phase 5 hardens the review workflow around the 5 platform roles
-- (DEVELOPER / STAFF / TECHNICAL_LEAD / MANAGER / ADMIN) combined with the
-- per-workspace role (MEMBER / MANAGER). Two corrections are needed:
--
-- 1) workspace_members.role is a VARCHAR(16) that historically received a
--    'STAFF' value. The Java enum `WorkspaceRole` only knows MEMBER and
--    MANAGER, so reading such a row throws an IllegalArgumentException
--    ("No enum constant WorkspaceRole.STAFF"). The platform tier (STAFF vs
--    MANAGER) now lives in user_roles; the workspace axis is only about
--    membership and workspace administration. Normalise the bad rows.
--
-- 2) The seeded permission matrix granted TECHNICAL_LEAD review rights but
--    not approve/publish rights. Under the new model a Technical Lead can
--    act at either tier of the two-tier review, so the matrix must match
--    what the service layer actually enforces.

-- ----------------------------------------------------------------------------
-- 1) Normalise invalid workspace role values.
--    'STAFF' was used as a proxy for "can review" — that capability now
--    comes from the platform role axis, so the workspace role degrades to
--    MEMBER. Any other unknown value is normalised the same way.
-- ----------------------------------------------------------------------------
UPDATE workspace_members
SET role = 'MEMBER'
WHERE role NOT IN ('MANAGER', 'MEMBER');

-- Guard the invariant going forward.
ALTER TABLE workspace_members
    DROP CONSTRAINT IF EXISTS chk_workspace_members_role;
ALTER TABLE workspace_members
    ADD CONSTRAINT chk_workspace_members_role
        CHECK (role IN ('MANAGER', 'MEMBER'));

-- ----------------------------------------------------------------------------
-- 2) Align the role-permission matrix with the enforced capability model.
--    TECHNICAL_LEAD gains approve + publish so the admin matrix shows the
--    same powers the API actually grants.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'TECHNICAL_LEAD'
  AND p.code IN ('DOCUMENT_VERSION_APPROVE', 'DOCUMENT_VERSION_PUBLISH')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
