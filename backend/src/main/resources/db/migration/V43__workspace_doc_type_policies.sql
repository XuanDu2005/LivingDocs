-- LivingDocs V43 — Per-workspace doc-type policies.
--
-- Problem this migration solves
-- ---------------------------
-- The doc-type workflow policy (AUTO_APPLY vs MANAGER_REVIEW) could only
-- be configured at the *platform* level, through
-- /admin/platform-settings/doc-type-policies. A workspace manager could
-- not decide, for their own workspace, that e.g. API_REFERENCE needs a
-- manager approval while README may still be auto-applied.
--
-- GovernancePolicyService.workflowFor(workspaceId, docType) already took
-- a workspace argument, but nothing ever wrote a workspace-scoped row:
-- there was no table, no entity and no repository, so the workspace
-- argument was decorative. Only the platform tier was configurable.
--
-- What is added here
-- ------------------
-- A table holding the per-workspace overrides. It follows the same
-- shape as `platform_doc_type_policies` so the resolution order is a
-- simple, explainable chain:
--
--   1. the workspace's own row for this doc type  (most specific)
--   2. the platform's row for this doc type
--   3. the workspace's `require_manager_approval` flag
--   4. the platform's `default_require_manager_approval` flag
--
-- The rows are created lazily by the manager when they pick a workflow,
-- so a workspace that never overrides anything keeps inheriting the
-- platform policy unchanged.

CREATE TABLE workspace_doc_type_policies (
    id              UUID         PRIMARY KEY,
    workspace_id    UUID         NOT NULL,
    doc_type        VARCHAR(40)  NOT NULL,
    -- AUTO_APPLY    — generated docs of this type are published without
    --                 a human decision (still subject to the confidence
    --                 gate).
    -- MANAGER_REVIEW — generated docs of this type stay a draft until a
    --                 manager approves them.
    workflow        VARCHAR(24)  NOT NULL DEFAULT 'MANAGER_REVIEW',
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_workspace_doc_type_policies UNIQUE (workspace_id, doc_type),
    CONSTRAINT chk_workspace_doc_type_workflow CHECK (workflow IN ('AUTO_APPLY','MANAGER_REVIEW')),
    CONSTRAINT fk_workspace_doc_type_policies_ws
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

-- The list endpoint reads every row of one workspace at once, and the
-- runtime looks up a single (workspace, doc_type) pair on every
-- generation. Both are covered by this index.
CREATE INDEX idx_workspace_doc_type_policies_ws
    ON workspace_doc_type_policies (workspace_id);
