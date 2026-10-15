-- LivingDocs V26 — Webhooks table (generic integrations).
--
-- Mirrors com.livingdocs.modules.integrations.model.Webhook. Do not
-- change column names without updating the entity.

CREATE TABLE webhooks (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    url TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_webhooks_workspace ON webhooks(workspace_id);
