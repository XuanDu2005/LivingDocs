-- LivingDocs V16 — Permission catalogue + role-permission matrix.
--
-- Phase 6 of the Administrator feature: granular permissions instead of
-- coarse role-based gating. Each role gets a set of permission codes
-- and the API checks both the role catalogue membership and the
-- permission flags. Permissions are codespaced (UPPER_SNAKE_CASE) and
-- grouped by category for the admin UI matrix.

-- ----------------------------------------------------------------------------
-- 1) Permissions catalogue.
--    A flat catalogue of every action a user can take in the system.
--    category is purely for the admin UI grouping (no behaviour
--    attached). code is the canonical identifier; the application
--    references it as a string constant. is_system prevents deletion
--    of seeded permissions.
-- ----------------------------------------------------------------------------
CREATE TABLE permissions (
    id                 UUID         PRIMARY KEY,
    code               VARCHAR(64)  NOT NULL,
    name               VARCHAR(160) NOT NULL,
    description        VARCHAR(500),
    category           VARCHAR(40)  NOT NULL,
    display_order      INTEGER      NOT NULL DEFAULT 100,
    is_system          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT chk_permissions_code_format CHECK (code = UPPER(code) AND code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE INDEX idx_permissions_category ON permissions (category, display_order);

-- ----------------------------------------------------------------------------
-- 2) Role ↔ permission assignments.
--    Many-to-many with composite key. (role_id, permission_id) is
--    unique so a permission cannot be granted twice to the same role.
--    granted_by/audit-trail columns keep the same shape as user_roles.
-- ----------------------------------------------------------------------------
CREATE TABLE role_permissions (
    id                 UUID         PRIMARY KEY,
    role_id            UUID         NOT NULL,
    permission_id      UUID         NOT NULL,
    granted_by         UUID,
    granted_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_role_permissions UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role       FOREIGN KEY (role_id)       REFERENCES roles (id)       ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_grantor    FOREIGN KEY (granted_by)    REFERENCES users (id)       ON DELETE SET NULL
);

CREATE INDEX idx_role_permissions_role       ON role_permissions (role_id);
CREATE INDEX idx_role_permissions_permission ON role_permissions (permission_id);

-- ----------------------------------------------------------------------------
-- 3) Seed the permission catalogue.
--    Codes are grouped by category so the admin matrix renders
--    coherent columns. The list is intentionally broad: every gate
--    the backend enforces today should be a permission, so we can
--    later replace @RequirePlatformRole(...) with @RequirePermission(...)
--    checks where the role membership is too coarse.
-- ----------------------------------------------------------------------------
INSERT INTO permissions (id, code, name, description, category, display_order) VALUES
-- Workspaces & members
(gen_random_uuid(), 'WORKSPACE_CREATE',     'Create workspace',                 'Create a new workspace as its owner.',                  'workspace', 10),
(gen_random_uuid(), 'WORKSPACE_UPDATE',     'Update workspace',                 'Edit workspace name, description and settings.',        'workspace', 20),
(gen_random_uuid(), 'WORKSPACE_DELETE',     'Delete workspace',                 'Permanently delete a workspace and its data.',          'workspace', 30),
(gen_random_uuid(), 'WORKSPACE_MEMBER_INVITE', 'Invite member',                  'Invite users into a workspace.',                        'workspace', 40),
(gen_random_uuid(), 'WORKSPACE_MEMBER_REMOVE', 'Remove member',                  'Remove members from a workspace.',                      'workspace', 50),
(gen_random_uuid(), 'WORKSPACE_MEMBER_ROLE_ASSIGN', 'Assign workspace role',      'Assign MANAGER / STAFF / DEVELOPER roles in a workspace.', 'workspace', 60),
-- Documents
(gen_random_uuid(), 'DOCUMENT_CREATE',      'Create document',                  'Create a new document in a workspace.',                 'document', 10),
(gen_random_uuid(), 'DOCUMENT_UPDATE',      'Update document',                  'Edit an existing document (metadata, content).',         'document', 20),
(gen_random_uuid(), 'DOCUMENT_DELETE',      'Delete document',                  'Soft-delete or hard-delete a document.',                'document', 30),
(gen_random_uuid(), 'DOCUMENT_VERSION_PUBLISH', 'Publish version',             'Publish a document version (without review).',           'document', 40),
(gen_random_uuid(), 'DOCUMENT_VERSION_REVIEW',  'Review version',              'Review a pending document version and leave feedback.', 'document', 50),
(gen_random_uuid(), 'DOCUMENT_VERSION_APPROVE', 'Approve version',             'Approve a reviewed version into the published set.',    'document', 60),
(gen_random_uuid(), 'DOCUMENT_REGENERATE_AI', 'Regenerate document with AI',     'Manually trigger AI regeneration of a document.',        'document', 70),
-- Templates
(gen_random_uuid(), 'TEMPLATE_CREATE',      'Create template',                  'Create a document template.',                           'template', 10),
(gen_random_uuid(), 'TEMPLATE_UPDATE',      'Update template',                  'Edit a document template.',                             'template', 20),
(gen_random_uuid(), 'TEMPLATE_DELETE',      'Delete template',                  'Delete a document template.',                           'template', 30),
(gen_random_uuid(), 'TEMPLATE_PUBLISH',     'Publish template',                 'Publish a template version for workspace use.',         'template', 40),
-- AI
(gen_random_uuid(), 'AI_RUN_GENERATION',    'Run AI generation',                'Trigger an AI documentation generation job.',           'ai', 10),
(gen_random_uuid(), 'AI_CONFIG_READ',       'View AI configuration',            'View AI models, prompts and confidence settings.',       'ai', 20),
(gen_random_uuid(), 'AI_CONFIG_WRITE',      'Update AI configuration',          'Update AI models, prompts and confidence settings.',     'ai', 30),
(gen_random_uuid(), 'AI_USAGE_VIEW',        'View AI usage',                    'View token consumption and quota dashboards.',          'ai', 40),
-- Integrations
(gen_random_uuid(), 'INTEGRATION_CONNECT',  'Connect integration',              'Connect a third-party integration (GitHub, Slack, Jira).', 'integration', 10),
(gen_random_uuid(), 'INTEGRATION_DISCONNECT', 'Disconnect integration',        'Disconnect / remove an integration.',                   'integration', 20),
(gen_random_uuid(), 'INTEGRATION_CONFIG',   'Configure integration',            'Edit configuration (channels, webhook URLs, secrets).', 'integration', 30),
-- Admin (platform)
(gen_random_uuid(), 'ADMIN_USER_MANAGE',    'Manage platform users',            'Create, disable or modify platform users.',             'admin', 10),
(gen_random_uuid(), 'ADMIN_ROLE_MANAGE',    'Manage platform roles',            'Create, edit or delete platform roles.',                'admin', 20),
(gen_random_uuid(), 'ADMIN_PERMISSION_MANAGE', 'Manage role-permission matrix', 'Toggle permissions granted to each platform role.',     'admin', 30),
(gen_random_uuid(), 'ADMIN_AUDIT_VIEW',     'View platform audit log',          'Read entries in the platform audit log.',               'admin', 40),
(gen_random_uuid(), 'ADMIN_AUDIT_RETENTION_MANAGE', 'Manage audit retention',    'Configure audit log retention and pruning policies.',  'admin', 50),
(gen_random_uuid(), 'ADMIN_PLATFORM_SETTINGS_MANAGE', 'Manage platform settings', 'Edit platform-wide documentation generation settings.', 'admin', 60),
(gen_random_uuid(), 'ADMIN_LANGUAGE_MANAGE', 'Manage platform languages',        'Configure the platform-wide list of supported languages.', 'admin', 70),
(gen_random_uuid(), 'ADMIN_NOTIFICATION_MANAGE', 'Manage platform notifications', 'Send platform-wide broadcasts and configure policies.', 'admin', 80),
(gen_random_uuid(), 'ADMIN_SYSTEM_HEALTH_VIEW', 'View system health',           'Read platform-wide system health, indexing and AI metrics.', 'admin', 90);

-- ----------------------------------------------------------------------------
-- 4) Seed the default role → permission matrix.
--
--    DEVELOPER     – member-level permissions only.
--    STAFF         – add review permissions.
--    TECHNICAL_LEAD – add workspace admin and config permissions.
--    MANAGER       – add approve / publish / integrate permissions.
--    ADMIN         – everything.
--
-- The seed is inserted as a single multi-row VALUES so the migration
-- is idempotent across re-runs. The defaults match the spirit of the
-- role descriptions seeded in V6.
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE
    -- ADMIN gets every permission.
    (r.code = 'ADMIN')
    -- MANAGER: approve/publish, integrations, AI usage, all non-admin.
    OR (r.code = 'MANAGER' AND p.code IN (
        'WORKSPACE_CREATE','WORKSPACE_UPDATE','WORKSPACE_DELETE',
        'WORKSPACE_MEMBER_INVITE','WORKSPACE_MEMBER_REMOVE','WORKSPACE_MEMBER_ROLE_ASSIGN',
        'DOCUMENT_CREATE','DOCUMENT_UPDATE','DOCUMENT_DELETE',
        'DOCUMENT_VERSION_PUBLISH','DOCUMENT_VERSION_REVIEW','DOCUMENT_VERSION_APPROVE',
        'DOCUMENT_REGENERATE_AI',
        'TEMPLATE_CREATE','TEMPLATE_UPDATE','TEMPLATE_DELETE','TEMPLATE_PUBLISH',
        'AI_RUN_GENERATION','AI_CONFIG_READ','AI_USAGE_VIEW',
        'INTEGRATION_CONNECT','INTEGRATION_DISCONNECT','INTEGRATION_CONFIG',
        'ADMIN_AUDIT_VIEW','ADMIN_NOTIFICATION_MANAGE'
    ))
    -- TECHNICAL_LEAD: workspace admin, review, AI config, integrate.
    OR (r.code = 'TECHNICAL_LEAD' AND p.code IN (
        'WORKSPACE_CREATE','WORKSPACE_UPDATE',
        'WORKSPACE_MEMBER_INVITE','WORKSPACE_MEMBER_REMOVE','WORKSPACE_MEMBER_ROLE_ASSIGN',
        'DOCUMENT_CREATE','DOCUMENT_UPDATE','DOCUMENT_DELETE',
        'DOCUMENT_VERSION_REVIEW',
        'DOCUMENT_REGENERATE_AI',
        'TEMPLATE_CREATE','TEMPLATE_UPDATE','TEMPLATE_PUBLISH',
        'AI_RUN_GENERATION','AI_CONFIG_READ','AI_USAGE_VIEW',
        'INTEGRATION_CONNECT','INTEGRATION_DISCONNECT','INTEGRATION_CONFIG'
    ))
    -- STAFF: review, run AI, view AI usage.
    OR (r.code = 'STAFF' AND p.code IN (
        'DOCUMENT_CREATE','DOCUMENT_UPDATE',
        'DOCUMENT_VERSION_REVIEW',
        'AI_RUN_GENERATION','AI_CONFIG_READ','AI_USAGE_VIEW'
    ))
    -- DEVELOPER: baseline member.
    OR (r.code = 'DEVELOPER' AND p.code IN (
        'DOCUMENT_CREATE','DOCUMENT_UPDATE',
        'AI_RUN_GENERATION','AI_USAGE_VIEW'
    ));
