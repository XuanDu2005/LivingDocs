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

/**
 * Every notification kind the platform can emit, mapped to the i18n key
 * that holds its human-readable label.
 *
 * The event kinds are chosen by the backend and mix two styles:
 * SCREAMING_SNAKE_CASE constants (`ADMIN_BROADCAST`) and dotted paths
 * (`drift.detected`). A dot is i18next's key separator, so it cannot be
 * used verbatim inside a translation key — it is normalised to `_` here.
 *
 * The backend is the source of truth for which kinds exist; a kind that is
 * missing from this map falls back to a normalised key.
 */
export const NOTIFICATION_KIND_LABEL_KEYS: Record<string, string> = {
  ADMIN_BROADCAST: 'ADMIN_BROADCAST',
  'drift.detected': 'drift_detected',
  'doc.generation.failed': 'doc_generation_failed',
  'github.tokenInvalid': 'github_tokenInvalid',
  'system.test': 'system_test',
  SCM_PR_EVENT: 'SCM_PR_EVENT',
  SCM_PUSH: 'SCM_PUSH',
  SCM_ISSUE_EVENT: 'SCM_ISSUE_EVENT',
  SCM_RELEASE: 'SCM_RELEASE',
  JIRA_ISSUE_EVENT: 'JIRA_ISSUE_EVENT',
  REVIEW_ASSIGNED: 'REVIEW_ASSIGNED',
  INDEXING_DONE: 'INDEXING_DONE',
  GITHUB_PR_EVENT: 'GITHUB_PR_EVENT',
  GITHUB_PUSH: 'GITHUB_PUSH',
  GITHUB_ISSUE_EVENT: 'GITHUB_ISSUE_EVENT',
  GITHUB_RELEASE: 'GITHUB_RELEASE',
  GITLAB_PUSH: 'GITLAB_PUSH',
  GITLAB_MR_EVENT: 'GITLAB_MR_EVENT',
  GITLAB_ISSUE_EVENT: 'GITLAB_ISSUE_EVENT',
};

/**
 * Build the i18n key holding the label for a notification event kind.
 *
 * @param eventKind raw kind as returned by the backend
 * @returns the key under `notifications.kindLabels`
 */
export function notificationKindLabelKey(eventKind: string): string {
  const known = NOTIFICATION_KIND_LABEL_KEYS[eventKind];
  if (known) return known;
  // Unknown kinds still need a translatable key — normalise whatever the
  // server sent into a flat, separator-free identifier.
  return eventKind.replace(/\./g, '_');
}

export const SCOPE_LABEL_KEYS: Record<NotificationPolicy['scope'], string> = {
  PLATFORM: 'PLATFORM',
  WORKSPACE: 'WORKSPACE',
  INHERITED: 'INHERITED',
  DEFAULT: 'DEFAULT',
};
