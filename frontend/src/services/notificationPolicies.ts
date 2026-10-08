import apiClient from './api';

export interface NotificationPolicy {
  eventKind: string;
  enabled: boolean;
  /** PLATFORM | WORKSPACE | INHERITED | DEFAULT — used to label the row in the UI. */
  scope: 'PLATFORM' | 'WORKSPACE' | 'INHERITED' | 'DEFAULT';
}

export const notificationPoliciesApi = {
  // ---- Admin (platform defaults) --------------------------------------

  async listAdminPolicies(): Promise<NotificationPolicy[]> {
    const { data } = await apiClient.get<NotificationPolicy[]>(
      '/admin/notification-policies',
    );
    return data;
  },

  async updateAdminPolicy(eventKind: string, enabled: boolean): Promise<NotificationPolicy> {
    const { data } = await apiClient.put<NotificationPolicy>(
      `/admin/notification-policies/${encodeURIComponent(eventKind)}`,
      { enabled },
    );
    return data;
  },

  // ---- Workspace overrides --------------------------------------------

  async listWorkspacePolicies(workspaceId: string): Promise<NotificationPolicy[]> {
    const { data } = await apiClient.get<NotificationPolicy[]>(
      `/workspaces/${workspaceId}/notification-policies`,
    );
    return data;
  },

  async updateWorkspacePolicy(
    workspaceId: string,
    eventKind: string,
    enabled: boolean,
  ): Promise<NotificationPolicy> {
    const { data } = await apiClient.put<NotificationPolicy>(
      `/workspaces/${workspaceId}/notification-policies/${encodeURIComponent(eventKind)}`,
      { enabled },
    );
    return data;
  },
};

/** Pretty label for each known notification kind. The server is the source
 *  of truth for which kinds exist; this map is purely cosmetic. */
export const NOTIFICATION_KIND_LABELS: Record<string, string> = {
  ADMIN_BROADCAST: 'Admin broadcast',
  'drift.detected': 'Drift detected',
  SCM_PR_EVENT: 'Pull request event (SCM)',
  SCM_PUSH: 'Push (SCM)',
  SCM_ISSUE_EVENT: 'Issue event (SCM)',
  SCM_RELEASE: 'Release published (SCM)',
  JIRA_ISSUE_EVENT: 'Jira issue updated',
  REVIEW_ASSIGNED: 'Review assigned',
  INDEXING_DONE: 'Indexing finished',
};

export const SCOPE_LABELS: Record<NotificationPolicy['scope'], string> = {
  PLATFORM: 'Platform default',
  WORKSPACE: 'Workspace override',
  INHERITED: 'Inherited from platform',
  DEFAULT: 'Default (enabled)',
};