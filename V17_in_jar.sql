-- LivingDocs V17 — Platform-wide defaults for documentation generation
-- governance (output format, default template binding, generation trigger).
--
-- Phase 5 follow-up: the spec says the admin can configure
--   (a) the default output format applied to newly-generated docs
--   (b) the default template per platform (binding to a global template id)
--   (c) when generation is triggered (manual / on-push / on-pr / on-merge /
--       scheduled)
-- in addition to the per-template triggers already present in V9.
--
-- We store these as platform-wide defaults copied into each new workspace;
-- each workspace can override via its own workspace_settings row.
-- Idempotent: uses DO blocks to skip if already applied.

-- ── platform_settings ──────────────────────────────────────────────────────────

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'platform_settings' AND column_name = 'default_output_format') THEN
    ALTER TABLE platform_settings ADD COLUMN default_output_format VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'platform_settings' AND column_name = 'default_template_id') THEN
    ALTER TABLE platform_settings ADD COLUMN default_template_id UUID NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'platform_settings' AND column_name = 'default_generation_trigger') THEN
    ALTER TABLE platform_settings ADD COLUMN default_generation_trigger VARCHAR(24) NOT NULL DEFAULT 'ON_PULL_REQUEST';
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                 WHERE constraint_name = 'chk_default_output_format') THEN
    ALTER TABLE platform_settings ADD CONSTRAINT chk_default_output_format
      CHECK (default_output_format IN ('MARKDOWN','HTML','PDF'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                 WHERE constraint_name = 'chk_default_generation_trigger') THEN
    ALTER TABLE platform_settings ADD CONSTRAINT chk_default_generation_trigger
      CHECK (default_generation_trigger IN ('MANUAL','ON_COMMIT','ON_PULL_REQUEST','ON_MERGE','SCHEDULED'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                 WHERE constraint_name = 'fk_platform_settings_default_template') THEN
    ALTER TABLE platform_settings ADD CONSTRAINT fk_platform_settings_default_template
      FOREIGN KEY (default_template_id) REFERENCES doc_templates(id)
      ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED;
  END IF;
END $$;

-- ── workspace_settings ──────────────────────────────────────────────────────────

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'workspace_settings' AND column_name = 'default_output_format') THEN
    ALTER TABLE workspace_settings ADD COLUMN default_output_format VARCHAR(16) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'workspace_settings' AND column_name = 'default_template_id') THEN
    ALTER TABLE workspace_settings ADD COLUMN default_template_id UUID NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'workspace_settings' AND column_name = 'default_generation_trigger') THEN
    ALTER TABLE workspace_settings ADD COLUMN default_generation_trigger VARCHAR(24) NULL;
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                 WHERE constraint_name = 'chk_ws_default_output_format') THEN
    ALTER TABLE workspace_settings ADD CONSTRAINT chk_ws_default_output_format
      CHECK (default_output_format IS NULL OR default_output_format IN ('MARKDOWN','HTML','PDF'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                 WHERE constraint_name = 'chk_ws_default_generation_trigger') THEN
    ALTER TABLE workspace_settings ADD CONSTRAINT chk_ws_default_generation_trigger
      CHECK (default_generation_trigger IS NULL OR default_generation_trigger IN ('MANUAL','ON_COMMIT','ON_PULL_REQUEST','ON_MERGE','SCHEDULED'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                 WHERE constraint_name = 'fk_workspace_settings_default_template') THEN
    ALTER TABLE workspace_settings ADD CONSTRAINT fk_workspace_settings_default_template
      FOREIGN KEY (default_template_id) REFERENCES doc_templates(id)
      ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED;
  END IF;
END $$;
