-- LivingDocs V36 — Capability permissions + tightened DEVELOPER grants.
--
-- Problem this migration solves
-- ---------------------------
-- V16 introduced a granular permission catalogue and seeded a
-- role → permission matrix, but nothing in the application *enforced*
-- it: every guard was either a platform-role check
-- (@RequirePlatformRole) or a workspace-membership check. The matrix was
-- therefore decorative, and a plain DEVELOPER could reach:
--
--   * /api/v1/system-health        — DB latency, AI endpoint URL, JVM
--                                    memory, thread count, job queue
--   * /api/v1/workspaces/{id}/ai-settings
--                                  — provider, base URL, API-key mask
--   * /api/v1/workspaces/{id}/audit-logs
--                                  — the workspace governance trail
--   * /api/v1/workspaces/{id}/analytics
--                                  — activity + drift aggregates
--   * /api/v1/workspaces/{id}/notification-policies
--                                  — platform policy overrides
--   * /api/v1/workspaces/{id}/integrations
--                                  — external account names, base URLs
--   * /api/v1/workspaces/{id}/governance
--                                  — the effective governance policy
--   * .../repositories/{id}/commit and .../doc-sync/apply
--                                  — publishing straight to GitHub
--
-- The first one is a direct operational-information leak; the last one
-- lets the lowest role push content to a production repository.
--
-- What this migration does
-- -----------------------
-- 1) Adds the permission codes that were missing for those surfaces.
-- 2) Grants them to the roles that legitimately need them.
-- 3) Removes the grants that let DEVELOPER see operational data.
--
-- The application change that goes with it is the @RequirePermission
-- annotation plus PermissionInterceptor, which resolve the matrix at
-- request time.

-- ----------------------------------------------------------------------------
-- 1) New permission codes.
--    Categories match the ones already used by the catalogue (workspace,
--    document, ai, template, integration, admin) plus two new ones for
--    the oversight surfaces (insight, governance, audit, notification,
--    repository).
-- ----------------------------------------------------------------------------
INSERT INTO permissions (id, code, name, description, category, display_order) VALUES
-- Oversight: what is going on in this workspace
(gen_random_uuid(), 'HEALTH_DASHBOARD_VIEW',   'View workspace health dashboard',   'Read document, drift and repository health metrics for a workspace.',        'insight', 10),
(gen_random_uuid(), 'ANALYTICS_VIEW',           'View workspace analytics',         'Read the aggregated activity, growth and drift trends of a workspace.',      'insight', 20),
-- Audit trail
(gen_random_uuid(), 'AUDIT_LOG_VIEW',            'View workspace audit log',         'Read the governance audit trail of a single workspace.',                     'audit', 10),
-- Governance policy
(gen_random_uuid(), 'GOVERNANCE_VIEW',          'View governance policy',           'Read the governance policy the runtime actually enforces for a workspace.',   'governance', 10),
(gen_random_uuid(), 'GOVERNANCE_MANAGE',        'Manage governance policy',         'Change the governance knobs: confidence threshold, merge policy, approval rule.', 'governance', 20),
-- Notification policies
(gen_random_uuid(), 'NOTIFICATION_POLICY_VIEW', 'View notification policies',       'Read the resolved notification policies and delivery channels.',             'notification', 10),
(gen_random_uuid(), 'NOTIFICATION_POLICY_MANAGE','Manage notification policies',     'Toggle notification policies and delivery channels for a workspace.',         'notification', 20),
-- Integrations (read side; connect/config already exist)
(gen_random_uuid(), 'INTEGRATION_VIEW',         'View integration connections',      'List the external accounts linked to a workspace and their status.',         'integration', 40),
-- AI token budget management
(gen_random_uuid(), 'AI_USAGE_MANAGE',          'Manage AI token budgets',          'Change the token budgets and rate limits of a workspace.',                      'ai', 50),
-- Repository publishing
(gen_random_uuid(), 'SCM_COMMIT',               'Publish documents to GitHub',       'Push a document to a repository as a branch and open a pull request.',         'repository', 10)
ON CONFLICT (code) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2) Grants for the new codes.
--
--    MANAGER       – owns the workspace: full oversight + publishing.
--    TECHNICAL_LEAD – owns the technical configuration: everything except
--                     the audit trail, which stays with MANAGER/ADMIN.
--    STAFF         – reviews content, so it needs the policy it reviews
--                     against (governance, health, notification policies)
--                     but no oversight aggregates and no publishing.
--    DEVELOPER     – gets nothing from this migration. It contributes
--                     documentation; it does not read operational data.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('MANAGER', 'TECHNICAL_LEAD', 'STAFF')
  AND (
        -- MANAGER and TECHNICAL_LEAD both get the full oversight set.
        (r.code IN ('MANAGER', 'TECHNICAL_LEAD') AND p.code IN (
            'HEALTH_DASHBOARD_VIEW',
            'ANALYTICS_VIEW',
            'GOVERNANCE_VIEW',
            'GOVERNANCE_MANAGE',
            'NOTIFICATION_POLICY_VIEW',
            'NOTIFICATION_POLICY_MANAGE',
            'INTEGRATION_VIEW',
            'SCM_COMMIT',
            'AI_USAGE_VIEW',
            'AI_USAGE_MANAGE'
        ))
        -- TECHNICAL_LEAD additionally reads the audit trail: it is the
        -- tier that owns the technical configuration, and the trail is
        -- how that configuration is audited.
        OR (r.code = 'TECHNICAL_LEAD' AND p.code IN (
            'AUDIT_LOG_VIEW'
        ))
        -- STAFF reviews content against the policy, so it needs to read
        -- the policy, the health of the content, and the notification
        -- rules that will route its review requests.
        OR (r.code = 'STAFF' AND p.code IN (
            'HEALTH_DASHBOARD_VIEW',
            'GOVERNANCE_VIEW',
            'NOTIFICATION_POLICY_VIEW'
        ))
        -- STAFF reviews content and therefore does not change the
        -- governance knobs itself.
      )
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );

-- ----------------------------------------------------------------------------
-- 2b) ADMIN receives every new permission.
--
--     V16 granted ADMIN the whole catalogue with a cross join, so the
--     new codes have to be granted explicitly here — otherwise the
--     matrix would show ADMIN missing permissions that
--     RolePermissionService pins to "all granted" the first time an
--     admin saves it.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );

-- ----------------------------------------------------------------------------
-- 3) Tighten DEVELOPER.
--
--    DEVELOPER keeps DOCUMENT_CREATE / DOCUMENT_UPDATE (contributing is
--    the whole point of the role) and AI_RUN_GENERATION (asking for a
--    draft is contributing too).
--
--    It loses:
--      * AI_USAGE_VIEW — token consumption and quota dashboards are
--        operational/cost data, not a developer's business.
--      * AI_CONFIG_READ — provider, base URL and the API-key mask are
--        configuration secrets of the workspace, not documentation.
--      * ADMIN_AUDIT_VIEW — the governance trail is for reviewers.
--
--    Any accidental grant of the new oversight codes to DEVELOPER is
--    also removed here, so the matrix stays correct even if it was
--    edited through the admin UI between V16 and this migration.
-- ----------------------------------------------------------------------------
DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'DEVELOPER'
  AND p.code IN (
      'AI_USAGE_VIEW',
      'AI_USAGE_MANAGE',
      'AI_CONFIG_READ',
      'AI_CONFIG_WRITE',
      'ADMIN_AUDIT_VIEW',
      'ADMIN_SYSTEM_HEALTH_VIEW',
      'ADMIN_NOTIFICATION_MANAGE',
      'ADMIN_AUDIT_RETENTION_MANAGE',
      'ADMIN_PLATFORM_SETTINGS_MANAGE',
      'INTEGRATION_CONNECT',
      'INTEGRATION_DISCONNECT',
      'INTEGRATION_CONFIG',
      'HEALTH_DASHBOARD_VIEW',
      'ANALYTICS_VIEW',
      'AUDIT_LOG_VIEW',
      'GOVERNANCE_VIEW',
      'GOVERNANCE_MANAGE',
      'NOTIFICATION_POLICY_VIEW',
      'NOTIFICATION_POLICY_MANAGE',
      'INTEGRATION_VIEW',
      'SCM_COMMIT'
  );

-- ----------------------------------------------------------------------------
-- 4) STAFF must not read the AI provider configuration.
--
--    STAFF reviews *content*; it does not operate the model. V16 gave
--    it AI_CONFIG_READ, which exposed the provider, the base URL and
--    the API-key mask. Reading the effective policy (threshold, merge
--    policy) stays possible through GOVERNANCE_VIEW, which is the part
--    a reviewer actually needs.
--
--    AI_CONFIG_WRITE was never granted to STAFF; it is asserted here so
--    the invariant is explicit.
-- ----------------------------------------------------------------------------
DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'STAFF'
  AND p.code IN ('AI_CONFIG_READ', 'AI_CONFIG_WRITE');

-- ----------------------------------------------------------------------------
-- 5) MANAGER / TECHNICAL_LEAD may change the AI configuration.
--
--    The API settings PUT endpoint already required the *workspace*
--    MANAGER role; the permission matrix never expressed that, so the
--    admin UI showed the toggle while the matrix said otherwise.
--    Align the matrix with the enforced capability.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('MANAGER', 'TECHNICAL_LEAD')
  AND p.code = 'AI_CONFIG_WRITE'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );
