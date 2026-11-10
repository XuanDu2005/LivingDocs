-- LivingDocs V52 — the DOCUMENTATION_OWNER workspace role.
--
-- Problem this migration solves
-- ---------------------------
-- The workspace axis only knew MEMBER and MANAGER, so every
-- documentation-governance surface (templates, doc-type workflows, the
-- governance settings) was locked behind the workspace MANAGER role.
-- A team that wanted one person to own the shape of the documentation
-- had to hand that person full workspace administration — membership,
-- repository connections, everything — because there was no narrower
-- role to give.
--
-- What this migration does
-- -----------------------
-- 1) Widens the role column: 'DOCUMENTATION_OWNER' is 20 characters
--    and did not fit into the VARCHAR(16) the column was created with.
-- 2) Extends the CHECK constraint that guards the allowed values, so
--    the invariant "the role column holds a value the Java enum knows"
--    keeps holding.
--
-- The role is orthogonal to the platform-role axis: it grants nothing on
-- its own. The capability axis still decides whether the person may
-- touch the governance knobs at all (GOVERNANCE_MANAGE), so the two
-- axes keep being combined with AND.

-- ----------------------------------------------------------------------------
-- 1) The column has to be wide enough for the new role.
-- ----------------------------------------------------------------------------
ALTER TABLE workspace_members
    ALTER COLUMN role TYPE VARCHAR(32);

-- ----------------------------------------------------------------------------
-- 2) Extend the guarded value set.
--
--    'MEMBER' and 'MANAGER' keep their meaning; 'DOCUMENTATION_OWNER' is
--    added between them. Any other value is normalised to MEMBER first,
--    so a hand-edited row cannot fail the CHECK constraint below and
--    take the whole migration down with it.
-- ----------------------------------------------------------------------------
UPDATE workspace_members
SET role = 'MEMBER'
WHERE role NOT IN ('MANAGER', 'DOCUMENTATION_OWNER', 'MEMBER');

ALTER TABLE workspace_members
    DROP CONSTRAINT IF EXISTS chk_workspace_members_role;
ALTER TABLE workspace_members
    ADD CONSTRAINT chk_workspace_members_role
        CHECK (role IN ('MANAGER', 'DOCUMENTATION_OWNER', 'MEMBER'));
