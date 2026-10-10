-- LivingDocs V15 — Platform-wide governance defaults.
--
-- Phase 5: the platform admin can set defaults for documentation
-- generation triggers and per-docType merge policies. New workspaces
-- inherit these on creation; existing workspaces keep their
-- per-workspace workspace_settings overrides.

CREATE TABLE platform_settings (
    id                                  INTEGER       PRIMARY KEY DEFAULT 1,
    -- Default auto-update triggers applied to a new workspace.
    default_auto_update_on_commit       BOOLEAN      NOT NULL DEFAULT FALSE,
    default_auto_update_on_pr           BOOLEAN      NOT NULL DEFAULT TRUE,
    default_auto_update_on_merge        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Default drift severity threshold for new workspaces.
    default_drift_severity_threshold    VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    -- Default manager-approval requirement.
    default_require_manager_approval    BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Default merge policy when drift is CRITICAL.
    default_merge_policy_critical       VARCHAR(16)  NOT NULL DEFAULT 'WARN',
    -- Default AI confidence threshold (0.0 - 1.0).
    default_ai_confidence_threshold     REAL         NOT NULL DEFAULT 0.70,
    -- Whether new workspaces start with require-approval ENABLED.
    -- Stored as a separate flag so we can model "auto-apply vs review"
    -- per doc type via the per-type table below.
    updated_at                          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_platform_settings_singleton CHECK (id = 1),
    CONSTRAINT chk_drift_threshold CHECK (
        default_drift_severity_threshold IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_merge_policy CHECK (
        default_merge_policy_critical IN ('WARN','BLOCK'))
);

-- Singleton row, seeded by V15.
INSERT INTO platform_settings (id) VALUES (1);

-- Per-docType policy: should docs of this type go through manager
-- review before publish, or auto-apply once generated?
CREATE TABLE platform_doc_type_policies (
    id                          UUID         PRIMARY KEY,
    doc_type                    VARCHAR(40)  NOT NULL,
    -- AUTO_APPLY   — generated docs are published without review.
    -- MANAGER_REVIEW — generated docs are queued for manager review.
    workflow                    VARCHAR(24)  NOT NULL DEFAULT 'MANAGER_REVIEW',
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_platform_doc_type_policies UNIQUE (doc_type),
    CONSTRAINT chk_workflow CHECK (workflow IN ('AUTO_APPLY','MANAGER_REVIEW'))
);

-- Seed the canonical DocType list with the safe default.
INSERT INTO platform_doc_type_policies (id, doc_type, workflow) VALUES
    (gen_random_uuid(), 'MODULE_GUIDE',   'MANAGER_REVIEW'),
    (gen_random_uuid(), 'API_REFERENCE',  'MANAGER_REVIEW'),
    (gen_random_uuid(), 'README',         'AUTO_APPLY'),
    (gen_random_uuid(), 'ARCHITECTURE',   'MANAGER_REVIEW'),
    (gen_random_uuid(), 'ADR',            'MANAGER_REVIEW'),
    (gen_random_uuid(), 'CHANGELOG',      'AUTO_APPLY'),
    (gen_random_uuid(), 'RUNBOOK',        'MANAGER_REVIEW'),
    (gen_random_uuid(), 'DATA_DICTION',   'MANAGER_REVIEW'),
    (gen_random_uuid(), 'TUTORIAL',       'MANAGER_REVIEW'),
    (gen_random_uuid(), 'CUSTOM',         'MANAGER_REVIEW');
