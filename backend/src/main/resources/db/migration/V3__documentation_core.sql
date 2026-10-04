-- LivingDocs V3 — Documentation core schema.
--
-- This migration introduces the persistent schema required by Phase 3+
-- (documents, templates, versions, reviews, drift detection, audit log,
-- notifications, code-document links). All new tables are created in the
-- public schema and reuse the project's existing conventions: UUID
-- primary keys, timestamptz audit columns, named foreign keys, and
-- dedicated indexes for the documented access patterns.

-- ----------------------------------------------------------------------------
-- 1) Documentation templates.
--    Reusable blueprints (API reference, README, module guide, ADR, ...)
--    that the AI must conform to when generating documentation.
-- ----------------------------------------------------------------------------
CREATE TABLE doc_templates (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID,
    name               VARCHAR(120) NOT NULL,
    slug               VARCHAR(140) NOT NULL,
    description        VARCHAR(500),
    doc_type           VARCHAR(40)  NOT NULL,
    version            INTEGER      NOT NULL DEFAULT 1,
    body               JSONB        NOT NULL,
    is_default         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by         UUID         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_doc_templates_slug_global UNIQUE (slug, version),
    CONSTRAINT fk_doc_templates_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_doc_templates_creator   FOREIGN KEY (created_by)   REFERENCES users (id)       ON DELETE RESTRICT
);

CREATE INDEX idx_doc_templates_workspace ON doc_templates (workspace_id);
CREATE INDEX idx_doc_templates_doc_type  ON doc_templates (doc_type);

-- ----------------------------------------------------------------------------
-- 2) Documents.
--    A document is the unit of review. Each document has a current
--    published version (head_version_id) and a workflow status. Documents
--    are scoped to a workspace and may be linked to a repository.
-- ----------------------------------------------------------------------------
CREATE TABLE documents (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID         NOT NULL,
    repository_id      UUID,
    template_id        UUID,
    title              VARCHAR(255) NOT NULL,
    slug               VARCHAR(280) NOT NULL,
    doc_type           VARCHAR(40)  NOT NULL,
    summary            VARCHAR(1000),
    status             VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    auto_update_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    owner_id           UUID         NOT NULL,
    head_version_id    UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at       TIMESTAMPTZ,
    CONSTRAINT uq_documents_workspace_slug UNIQUE (workspace_id, slug),
    CONSTRAINT fk_documents_workspace      FOREIGN KEY (workspace_id)  REFERENCES workspaces (id)    ON DELETE CASCADE,
    CONSTRAINT fk_documents_repository     FOREIGN KEY (repository_id) REFERENCES repositories (id)  ON DELETE SET NULL,
    CONSTRAINT fk_documents_template       FOREIGN KEY (template_id)   REFERENCES doc_templates (id) ON DELETE SET NULL,
    CONSTRAINT fk_documents_owner          FOREIGN KEY (owner_id)      REFERENCES users (id)         ON DELETE RESTRICT
);

CREATE INDEX idx_documents_workspace    ON documents (workspace_id);
CREATE INDEX idx_documents_repository   ON documents (repository_id);
CREATE INDEX idx_documents_status       ON documents (status);
CREATE INDEX idx_documents_doc_type     ON documents (doc_type);
CREATE INDEX idx_documents_owner        ON documents (owner_id);

-- ----------------------------------------------------------------------------
-- 3) Document versions (change log).
--    Every published, AI-generated, AI-updated or human-edited version is
--    recorded here. The acting role (AI, STAFF, MANAGER) is captured so any
--    modification is auditable and rollback is supported.
-- ----------------------------------------------------------------------------
CREATE TABLE document_versions (
    id                 UUID PRIMARY KEY,
    document_id        UUID         NOT NULL,
    version_number     INTEGER      NOT NULL,
    body_markdown      TEXT         NOT NULL,
    change_summary     VARCHAR(500),
    actor_role         VARCHAR(16)  NOT NULL,
    actor_user_id      UUID,
    source_commit_sha  VARCHAR(80),
    source_pr_id       UUID,
    template_id        UUID,
    status             VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    confidence_score   REAL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_document_versions UNIQUE (document_id, version_number),
    CONSTRAINT fk_doc_versions_document FOREIGN KEY (document_id)   REFERENCES documents (id)      ON DELETE CASCADE,
    CONSTRAINT fk_doc_versions_template FOREIGN KEY (template_id)   REFERENCES doc_templates (id) ON DELETE SET NULL,
    CONSTRAINT fk_doc_versions_actor    FOREIGN KEY (actor_user_id) REFERENCES users (id)         ON DELETE SET NULL,
    CONSTRAINT fk_doc_versions_pr       FOREIGN KEY (source_pr_id)  REFERENCES pull_requests (id) ON DELETE SET NULL
);

CREATE INDEX idx_document_versions_doc ON document_versions (document_id);
CREATE INDEX idx_document_versions_actor_role ON document_versions (actor_role);
CREATE INDEX idx_document_versions_created_at ON document_versions (created_at DESC);

-- Now wire the documents.head_version_id pointer back to document_versions.
ALTER TABLE documents
    ADD CONSTRAINT fk_documents_head_version FOREIGN KEY (head_version_id) REFERENCES document_versions (id) ON DELETE SET NULL;

-- ----------------------------------------------------------------------------
-- 4) Document reviews (workflow).
--    Each review entry is one Staff review pass and one Manager approval.
--    Multiple review records may exist per document version (e.g. comments
--    added while in review) but only one OPEN record is allowed via the
--    partial unique index below.
-- ----------------------------------------------------------------------------
CREATE TABLE document_reviews (
    id                 UUID PRIMARY KEY,
    document_version_id UUID        NOT NULL,
    reviewer_user_id   UUID         NOT NULL,
    reviewer_role      VARCHAR(16)  NOT NULL,
    decision           VARCHAR(24)  NOT NULL,
    comment            TEXT,
    decided_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_doc_reviews_version  FOREIGN KEY (document_version_id) REFERENCES document_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_doc_reviews_reviewer FOREIGN KEY (reviewer_user_id)    REFERENCES users (id)            ON DELETE RESTRICT
);

CREATE INDEX idx_document_reviews_version  ON document_reviews (document_version_id);
CREATE INDEX idx_document_reviews_reviewer ON document_reviews (reviewer_user_id);

-- ----------------------------------------------------------------------------
-- 5) Code entities.
--    Snapshots of code elements extracted by the AST analyser, scoped to
--    a repository at a specific commit. Used to compute drift and to power
--    the knowledge base.
-- ----------------------------------------------------------------------------
CREATE TABLE code_entities (
    id                 UUID PRIMARY KEY,
    repository_id      UUID         NOT NULL,
    commit_sha         VARCHAR(80)  NOT NULL,
    language           VARCHAR(40)  NOT NULL,
    entity_type        VARCHAR(40)  NOT NULL,
    qualified_name     VARCHAR(500) NOT NULL,
    simple_name        VARCHAR(200) NOT NULL,
    file_path          VARCHAR(1000) NOT NULL,
    start_line         INTEGER      NOT NULL,
    end_line           INTEGER      NOT NULL,
    signature          TEXT,
    docstring          TEXT,
    metadata           JSONB,
    ingested_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_code_entities UNIQUE (repository_id, commit_sha, qualified_name, file_path, start_line),
    CONSTRAINT fk_code_entities_repo FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE CASCADE
);

CREATE INDEX idx_code_entities_repo    ON code_entities (repository_id);
CREATE INDEX idx_code_entities_commit  ON code_entities (commit_sha);
CREATE INDEX idx_code_entities_qname   ON code_entities (qualified_name);
CREATE INDEX idx_code_entities_type    ON code_entities (entity_type);

-- ----------------------------------------------------------------------------
-- 6) Code-Document links.
--    Explicit relationships between a document version and the code
--    entities it references. Used to compute drift impact, navigate
--    doc → code and back, and power the knowledge base.
-- ----------------------------------------------------------------------------
CREATE TABLE code_document_links (
    id                 UUID PRIMARY KEY,
    document_id        UUID         NOT NULL,
    code_entity_id     UUID         NOT NULL,
    link_kind          VARCHAR(40)  NOT NULL DEFAULT 'REFERENCE',
    confidence         REAL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_code_document_link UNIQUE (document_id, code_entity_id),
    CONSTRAINT fk_cdl_document    FOREIGN KEY (document_id)    REFERENCES documents (id)     ON DELETE CASCADE,
    CONSTRAINT fk_cdl_code_entity FOREIGN KEY (code_entity_id) REFERENCES code_entities (id) ON DELETE CASCADE
);

CREATE INDEX idx_code_document_links_doc    ON code_document_links (document_id);
CREATE INDEX idx_code_document_links_entity ON code_document_links (code_entity_id);

-- ----------------------------------------------------------------------------
-- 7) Documentation drift alerts.
--    When a code change potentially breaks a document, an entry is created
--    here. Severity + drift kind come from the analyser; resolution_status
--    tracks the lifecycle (OPEN → ACCEPTED / DISMISSED / FIXED).
-- ----------------------------------------------------------------------------
CREATE TABLE drift_alerts (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID         NOT NULL,
    repository_id      UUID         NOT NULL,
    pull_request_id    UUID,
    document_id        UUID         NOT NULL,
    drift_kind         VARCHAR(40)  NOT NULL,
    severity           VARCHAR(16)  NOT NULL,
    title              VARCHAR(255) NOT NULL,
    description        TEXT         NOT NULL,
    evidence           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    resolution_status  VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
    ai_suggestion      TEXT,
    confidence_score   REAL,
    detected_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ,
    resolved_by        UUID,
    CONSTRAINT fk_drift_workspace    FOREIGN KEY (workspace_id)    REFERENCES workspaces (id)     ON DELETE CASCADE,
    CONSTRAINT fk_drift_repository   FOREIGN KEY (repository_id)   REFERENCES repositories (id)   ON DELETE CASCADE,
    CONSTRAINT fk_drift_document     FOREIGN KEY (document_id)     REFERENCES documents (id)      ON DELETE CASCADE,
    CONSTRAINT fk_drift_pr           FOREIGN KEY (pull_request_id) REFERENCES pull_requests (id) ON DELETE SET NULL,
    CONSTRAINT fk_drift_resolver     FOREIGN KEY (resolved_by)     REFERENCES users (id)         ON DELETE SET NULL
);

CREATE INDEX idx_drift_workspace   ON drift_alerts (workspace_id);
CREATE INDEX idx_drift_repository  ON drift_alerts (repository_id);
CREATE INDEX idx_drift_document    ON drift_alerts (document_id);
CREATE INDEX idx_drift_status      ON drift_alerts (resolution_status);
CREATE INDEX idx_drift_severity    ON drift_alerts (severity);
CREATE INDEX idx_drift_detected    ON drift_alerts (detected_at DESC);

-- ----------------------------------------------------------------------------
-- 8) Audit log.
--    Append-only trail of every administrative or governance action.
-- ----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id                 UUID PRIMARY KEY,
    actor_user_id      UUID,
    actor_role         VARCHAR(40),
    action             VARCHAR(80)  NOT NULL,
    resource_type      VARCHAR(80)  NOT NULL,
    resource_id        VARCHAR(120),
    workspace_id       UUID,
    payload            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_audit_actor     FOREIGN KEY (actor_user_id) REFERENCES users (id)      ON DELETE SET NULL,
    CONSTRAINT fk_audit_workspace FOREIGN KEY (workspace_id)  REFERENCES workspaces (id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_logs_actor     ON audit_logs (actor_user_id);
CREATE INDEX idx_audit_logs_workspace ON audit_logs (workspace_id);
CREATE INDEX idx_audit_logs_action    ON audit_logs (action);
CREATE INDEX idx_audit_logs_created   ON audit_logs (created_at DESC);

-- ----------------------------------------------------------------------------
-- 9) Notifications.
--    Per-user notifications: drift alerts, review assignments, system
--    events. The frontend polls or subscribes to /api/v1/notifications.
-- ----------------------------------------------------------------------------
CREATE TABLE notifications (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL,
    kind               VARCHAR(60)  NOT NULL,
    title              VARCHAR(255) NOT NULL,
    body               TEXT,
    link               VARCHAR(500),
    read_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_notifications_user_unread ON notifications (user_id, read_at);
CREATE INDEX idx_notifications_created     ON notifications (created_at DESC);

-- ----------------------------------------------------------------------------
-- 10) App settings (per-workspace governance knobs).
--     Stores JSON-encoded configuration: drift thresholds, auto-update
--     policy, merge policy, AI settings overrides, etc.
-- ----------------------------------------------------------------------------
CREATE TABLE workspace_settings (
    workspace_id       UUID         PRIMARY KEY,
    drift_severity_threshold  VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    auto_update_on_pr          BOOLEAN      NOT NULL DEFAULT TRUE,
    auto_update_on_commit      BOOLEAN      NOT NULL DEFAULT FALSE,
    require_manager_approval   BOOLEAN      NOT NULL DEFAULT TRUE,
    merge_policy_critical      VARCHAR(16)  NOT NULL DEFAULT 'WARN',
    ai_confidence_threshold    REAL         NOT NULL DEFAULT 0.70,
    extra                       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ws_settings_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);