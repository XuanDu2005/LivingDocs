-- LivingDocs V37 — author capabilities for the DEVELOPER role.
--
-- Problem this migration solves
-- ---------------------------
-- The V36 migration closed the *oversight* leaks (health dashboard, audit
-- trail, AI configuration) by introducing the @RequirePermission
-- interceptor. But the DEVELOPER role still could not do the things the
-- product specification asks an author to do:
--
--   * dismiss an AI draft that turned out to be wrong
--   * roll a document back to the version that existed before an
--     automatic update landed
--   * regenerate a document on demand
--   * turn AI auto-update on or off for a repository, or for a single
--     module / package inside it
--   * run a drift scan and pick the pull request it compares against
--   * review and change the repository access scopes of the GitHub
--     connection
--
-- Each of those had an API that was either missing or gated behind the
-- Manager tier, so the UI either showed a button that answered 403 or
-- showed no button at all.
--
-- What this migration does
-- -----------------------
-- 1) Registers the new permission codes.
-- 2) Grants them to the roles that legitimately need them. DEVELOPER
--    gets the author-facing set; the oversight roles keep theirs.
-- 3) Adds the tables the new capabilities need:
--      * repository_modules  — per module/package auto-update toggle
--      * github_scope_grants — the scopes a user granted to the app
--    4) Widens document_versions.actor_role so DEVELOPER fits (it was
--    VARCHAR(16), "DEVELOPER" is 9 characters, so it already did —
--    the column is only touched to document the invariant).
--
-- The application change that goes with it is:
--   * ActorRole.DEVELOPER, so a developer's edit is attributed to the
--     developer instead of to STAFF
--   * DocumentVersionService.dismissVersion
--   * a rollback that requires DOCUMENT_ROLLBACK rather than the
--     Manager approval tier
--   * ModuleAutoUpdateService
--   * GithubScopeService

-- ----------------------------------------------------------------------------
-- 1) New permission codes.
-- ----------------------------------------------------------------------------
INSERT INTO permissions (id, code, name, description, category, display_order) VALUES
-- Author-facing capabilities (document category)
(gen_random_uuid(), 'DOCUMENT_VERSION_DISMISS',  'Dismiss document draft',       'Discard an AI draft version without publishing it.',                       'document', 80),
(gen_random_uuid(), 'DOCUMENT_REGENERATE_AI',     'Regenerate document with AI',  'Manually trigger AI regeneration of a document.',                            'document', 90),
(gen_random_uuid(), 'DOCUMENT_ROLLBACK',          'Roll back document',           'Restore a document to a previous published version.',                       'document', 100),
-- Repository / module auto-update (repository category)
(gen_random_uuid(), 'REPOSITORY_AUTO_UPDATE_MANAGE', 'Manage repository auto-update', 'Enable or disable AI auto-update for a repository.',                   'repository', 20),
(gen_random_uuid(), 'MODULE_AUTO_UPDATE_MANAGE',  'Manage module auto-update',    'Enable or disable AI auto-update for a module or package.',                  'repository', 30),
-- Drift
(gen_random_uuid(), 'DRIFT_DETECT_RUN',            'Run drift detection',          'Trigger a drift detection pass manually or from a pull request.',            'insight', 30),
-- GitHub connection scopes (integration category)
(gen_random_uuid(), 'GITHUB_SCOPE_VIEW',           'View GitHub scopes',           'Review the repository access scopes granted to the GitHub connection.',      'integration', 50),
(gen_random_uuid(), 'GITHUB_SCOPE_MANAGE',         'Manage GitHub scopes',         'Change the repository access scopes of the GitHub connection.',              'integration', 60)
ON CONFLICT (code) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2) Grants.
--
--    DEVELOPER     – the author: contributes, regenerates, dismisses
--                    drafts, rolls back a bad auto-update, toggles
--                    auto-update for a repository or a module, runs a
--                    drift scan, reviews their own GitHub scopes.
--    STAFF        – reviews content, so it dismisses drafts and runs
--                    drift scans too, but does not change the GitHub
--                    connection's scopes.
--    TECHNICAL_LEAD / MANAGER – everything above plus managing the
--                    GitHub scopes.
--    ADMIN        – everything (granted by the cross join below).
-- ----------------------------------------------------------------------------
INSERT INTO role_permissions (id, role_id, permission_id)
SELECT gen_random_uuid(), r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE (
        -- The author-facing set every content role needs.
        (r.code IN ('DEVELOPER', 'STAFF', 'TECHNICAL_LEAD', 'MANAGER') AND p.code IN (
            'DOCUMENT_VERSION_DISMISS',
            'DOCUMENT_REGENERATE_AI',
            'DOCUMENT_ROLLBACK',
            'REPOSITORY_AUTO_UPDATE_MANAGE',
            'MODULE_AUTO_UPDATE_MANAGE',
            'DRIFT_DETECT_RUN'
        ))
        -- Reviewing the GitHub scopes is part of managing the connection,
        -- which is the technical lead's / manager's job.
        OR (r.code IN ('TECHNICAL_LEAD', 'MANAGER') AND p.code IN (
            'GITHUB_SCOPE_VIEW',
            'GITHUB_SCOPE_MANAGE'
        ))
        -- A developer may still *see* which scopes their own connection
        -- granted, so the "review the scopes" screen is not a 403 wall.
        OR (r.code = 'DEVELOPER' AND p.code IN (
            'GITHUB_SCOPE_VIEW'
        ))
  )
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
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
    );

-- ----------------------------------------------------------------------------
-- 3) Per-module / per-package auto-update.
--
--    The repository toggle and the document toggle already existed. This
--    adds the middle level: a directory inside the repository (a Java
--    package, a Python package, a Go directory) whose documents can be
--    excluded from auto-update while the rest of the repository keeps
--    updating.
--
--    A module is identified by its path prefix relative to the
--    repository root. The path is normalised to start with "/" and to
--    carry no trailing "/", so "/src/auth" and "src/auth/" address the
--    same module.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS repository_modules (
    id                  uuid PRIMARY KEY,
    repository_id       uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    workspace_id        uuid        NOT NULL REFERENCES workspaces(id)    ON DELETE CASCADE,
    module_path         varchar(500) NOT NULL,
    display_name        varchar(255),
    auto_update_enabled boolean     NOT NULL DEFAULT TRUE,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_repository_modules_path UNIQUE (repository_id, module_path)
);

CREATE INDEX IF NOT EXISTS idx_repository_modules_repo
    ON repository_modules (repository_id);

CREATE INDEX IF NOT EXISTS idx_repository_modules_workspace
    ON repository_modules (workspace_id);

-- ----------------------------------------------------------------------------
-- 4) GitHub scope grants.
--
--    The OAuth token carries a fixed set of scopes today. This table
--    records what the user actually granted, so the UI can show a
--    review screen ("you granted LivingDocs access to your repositories,
--    your organizations and your profile") and so a later change to the
--    requested scope set can be presented as a delta rather than as a
--    silent widening.
--
--    One row per (user, scope). The primary key keeps the grants unique
--    and makes "revoke one scope" a single DELETE.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS github_scope_grants (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope       varchar(120) NOT NULL,
    description varchar(500),
    granted_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    CONSTRAINT uq_github_scope_grants UNIQUE (user_id, scope)
);

CREATE INDEX IF NOT EXISTS idx_github_scope_grants_user
    ON github_scope_grants (user_id);

-- Seed the grants of every existing connection from the scope string
-- GitHub returned, so the review screen has content on the first run
-- instead of an empty list.
INSERT INTO github_scope_grants (id, user_id, scope, description)
SELECT gen_random_uuid(), c.user_id, s.scope,
       CASE s.scope
           WHEN 'repo'           THEN 'Full control of private repositories'
           WHEN 'read:user'      THEN 'Read your profile data'
           WHEN 'read:org'       THEN 'Read your organization membership'
           WHEN 'public_repo'    THEN 'Read and write access to public repositories'
           WHEN 'workflow'       THEN 'Add/update workflow files'
           WHEN 'read:main'      THEN 'Read the contents of the repository'
           WHEN 'admin:org'      THEN 'Full control of organization membership'
           ELSE 'GitHub scope ' || s.scope
       END
FROM github_connections c
CROSS JOIN LATERAL unnest(string_to_array(coalesce(c.scope, ''), ',')) AS s(scope)
WHERE length(trim(s.scope)) > 0
ON CONFLICT (user_id, scope) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 5) Document the actor_role invariant.
--
--    The column is VARCHAR(16); the longest value is "DEVELOPER" (9).
--    The CHECK constraint makes the invariant explicit so a future
--    rename cannot silently truncate.
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_document_versions_actor_role'
    ) THEN
        ALTER TABLE document_versions
            ADD CONSTRAINT chk_document_versions_actor_role
            CHECK (actor_role::text = ANY (ARRAY[
                'AI'::varchar, 'STAFF'::varchar, 'DEVELOPER'::varchar,
                'MANAGER'::varchar, 'SYSTEM'::varchar
            ]));
    END IF;
END $$;
