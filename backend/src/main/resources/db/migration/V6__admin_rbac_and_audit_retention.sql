-- LivingDocs V6 — Platform RBAC + Audit log retention.
--
-- Phase 1 of the Administrator feature:
--   * A real role catalogue so ADMIN / STAFF / MANAGER / TECHNICAL_LEAD /
--     DEVELOPER are first-class platform roles (no longer piggy-backed on
--     WorkspaceMember.role).
--   * Per-user role assignments with audit trail of who assigned what.
--   * Per-entity audit-log retention policies so admins can control how
--     long each audit table keeps rows before pruning.
--
-- All new tables follow the project conventions: UUID PKs, timestamptz
-- audit columns, named foreign keys, dedicated indexes for the documented
-- access patterns.

-- ----------------------------------------------------------------------------
-- 1) Roles.
--    Catalogue of all platform roles. The code column is the canonical
--    identifier (uppercase, underscore_separated) and is what the
--    application code stores and compares. New roles are inserted by
--    future migrations — never update the code of a seeded role because
--     that breaks JWT compatibility.
-- ----------------------------------------------------------------------------
CREATE TABLE roles (
    id                 UUID         PRIMARY KEY,
    code               VARCHAR(32)  NOT NULL,
    name               VARCHAR(120) NOT NULL,
    description        VARCHAR(500),
    is_system          BOOLEAN      NOT NULL DEFAULT FALSE,
    display_order      INTEGER      NOT NULL DEFAULT 100,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_roles_code UNIQUE (code),
    CONSTRAINT chk_roles_code_format CHECK (code = UPPER(code) AND code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE INDEX idx_roles_display_order ON roles (display_order);

-- Seed the five platform roles called out in section 5 of the
-- Administrator scope. display_order mirrors the privilege ladder so the
-- admin UI lists them from least → most privileged.
INSERT INTO roles (id, code, name, description, is_system, display_order) VALUES
    (gen_random_uuid(), 'DEVELOPER',
     'Developer',
     'Default member. Can view and contribute to documentation in workspaces they belong to.',
     TRUE, 10),
    (gen_random_uuid(), 'STAFF',
     'Staff (Reviewer)',
     'Reviews documentation changes submitted by AI or Developers in the approval workflow.',
     TRUE, 20),
    (gen_random_uuid(), 'TECHNICAL_LEAD',
     'Technical Lead',
     'Combines Staff review duties with the ability to approve documentation changes and manage workspace technical settings.',
     TRUE, 30),
    (gen_random_uuid(), 'MANAGER',
     'Manager (Approver)',
     'Final approver in the documentation approval workflow. Manages workspace membership and repository connections.',
     TRUE, 40),
    (gen_random_uuid(), 'ADMIN',
     'Administrator',
     'Full platform access. Manages users, roles, integrations, AI settings and audit retention.',
     TRUE, 50);

-- ----------------------------------------------------------------------------
-- 2) User role assignments.
--    A user may hold multiple platform roles. The (user_id, role_id)
--    pair is unique so the same role cannot be granted twice.
--    assigned_by records the administrator that granted the role so the
--    audit trail is intact.
-- ----------------------------------------------------------------------------
CREATE TABLE user_roles (
    id                 UUID         PRIMARY KEY,
    user_id            UUID         NOT NULL,
    role_id            UUID         NOT NULL,
    assigned_by        UUID,
    assigned_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at         TIMESTAMPTZ,
    CONSTRAINT uq_user_roles UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_roles_user    FOREIGN KEY (user_id)     REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role    FOREIGN KEY (role_id)     REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_grantor FOREIGN KEY (assigned_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_user_roles_user      ON user_roles (user_id);
CREATE INDEX idx_user_roles_role      ON user_roles (role_id);
CREATE INDEX idx_user_roles_active    ON user_roles (user_id) WHERE revoked_at IS NULL;

-- ----------------------------------------------------------------------------
-- 3) Audit log retention policies.
--    One row per (entity_type) describing how long records of that
--    entity should be retained before pruning. entity_type matches the
--    resource_type column of audit_logs (or a higher-level logical
--    bucket such as 'audit_log', 'notification', 'webhook_event').
--    retention_days NULL means "keep forever" (e.g. legal hold).
-- ----------------------------------------------------------------------------
CREATE TABLE audit_retention_policies (
    id                 UUID         PRIMARY KEY,
    entity_type        VARCHAR(80)  NOT NULL,
    description        VARCHAR(500),
    retention_days     INTEGER      NOT NULL,
    prune_strategy     VARCHAR(24)  NOT NULL DEFAULT 'HARD_DELETE',
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    last_pruned_at     TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_audit_retention_entity UNIQUE (entity_type),
    CONSTRAINT chk_audit_retention_days  CHECK (retention_days >= 0),
    CONSTRAINT chk_audit_retention_strategy CHECK (prune_strategy IN ('HARD_DELETE', 'ARCHIVE', 'ANONYMIZE'))
);

CREATE INDEX idx_audit_retention_enabled ON audit_retention_policies (enabled);

-- Seed retention defaults that match the policies documented in the
-- admin scope: audit logs kept ~365 days, change-log versions kept
-- ~180 days, webhook events kept ~30 days. NULL means keep forever,
-- but we want defaults that match "compliance minimums" of typical
-- documentation platforms.
INSERT INTO audit_retention_policies (id, entity_type, description, retention_days, prune_strategy, enabled) VALUES
    (gen_random_uuid(), 'audit_log',
     'Platform audit log entries. Increase for compliance regimes.',
     365, 'HARD_DELETE', TRUE),
    (gen_random_uuid(), 'document_version',
     'Change-log entries for document versions.',
     180, 'HARD_DELETE', TRUE),
    (gen_random_uuid(), 'notification',
     'In-app notifications.',
     90, 'HARD_DELETE', TRUE),
    (gen_random_uuid(), 'webhook_event',
     'Raw webhook payloads received from GitHub.',
     30, 'HARD_DELETE', TRUE),
    (gen_random_uuid(), 'drift_alert',
     'Resolved drift alerts.',
     365, 'HARD_DELETE', TRUE);

-- ----------------------------------------------------------------------------
-- 4) Promote existing administrators.
--    The application code seeds the first administrator through a
--    Flyway-aware callback, but we also promote any user whose email
--    matches the configured bootstrap admin email (LIVINGDOCS_ADMIN_EMAIL).
--    Keeping that as a runtime concern — this migration only makes the
--    ADMIN role resolvable.
-- ----------------------------------------------------------------------------
-- (handled by Spring Boot's ApplicationRunner after the migration completes.)