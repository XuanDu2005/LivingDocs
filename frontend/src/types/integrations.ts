// Types for the integrations module (GitHub / GitLab / Jira / Slack).

export type IntegrationProvider = 'GITHUB' | 'GITLAB' | 'JIRA' | 'SLACK';

export type IntegrationStatus = 'ACTIVE' | 'REVOKED' | 'ERROR';

export interface Connection {
  id: string;
  provider: IntegrationProvider;
  workspaceId: string | null;
  displayName: string;
  externalAccount: string;
  baseUrl: string | null;
  scopes: string | null;
  hasAccessToken: boolean;
  hasRefreshToken: boolean;
  hasWebhookSecret: boolean;
  defaultChannel: string | null;
  status: IntegrationStatus;
  connectedAt: string;
  lastUsedAt: string | null;
  lastSyncedAt: string | null;
}

export interface EventRow {
  id: string;
  connectionId: string | null;
  provider: IntegrationProvider;
  eventType: string;
  action: string | null;
  status: 'PENDING' | 'PROCESSED' | 'FAILED' | 'IGNORED';
  errorMessage: string | null;
  payloadSummary: string | null;
  receivedAt: string;
  processedAt: string | null;
}

export interface ConnectIntegrationPayload {
  provider: IntegrationProvider;
  workspaceId?: string | null;
  displayName: string;
  externalAccount: string;
  baseUrl?: string | null;
  scopes?: string | null;
  /** Plaintext — never read back. */
  accessToken?: string | null;
  refreshToken?: string | null;
  webhookSecret?: string | null;
  defaultChannel?: string | null;
}

export interface TestIntegrationResult {
  ok: boolean;
  message: string;
}

export const PROVIDER_LABELS: Record<IntegrationProvider, string> = {
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
  JIRA: 'Jira',
  SLACK: 'Slack',
};

export const PROVIDER_DESCRIPTIONS: Record<IntegrationProvider, string> = {
  GITHUB:
    'Receive pull-request, push, and issue events from a repository. Webhook URL: /api/v1/integrations/webhook/github',
  GITLAB:
    'Receive push, merge-request, and issue events from a project. Webhook URL: /api/v1/integrations/webhook/gitlab',
  JIRA:
    'Receive issue created / updated events from a Jira Cloud site. Webhook URL: /api/v1/integrations/webhook/jira',
  SLACK:
    'Mirror platform notifications to a single Slack channel. Webhook URL: /api/v1/integrations/webhook/slack',
};

export const STATUS_LABELS: Record<IntegrationStatus, string> = {
  ACTIVE: 'Active',
  REVOKED: 'Revoked',
  ERROR: 'Error',
};