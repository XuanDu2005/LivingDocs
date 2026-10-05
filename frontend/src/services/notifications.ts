/**
 * Notifications + audit log API client.
 */
import apiClient from './api';

export interface Notification {
  id: string;
  userId: string;
  kind: string;
  title: string;
  body: string | null;
  link: string | null;
  readAt: string | null;
  createdAt: string;
}

export const notificationsApi = {
  list(onlyUnread = false) {
    const qs = onlyUnread ? '?onlyUnread=true' : '';
    return apiClient
      .get<Notification[]>(`/notifications${qs}`)
      .then((r) => r.data);
  },
};

export interface AuditLog {
  id: string;
  actorUserId: string | null;
  actorRole: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  workspaceId: string | null;
  payload: string;
  createdAt: string;
}

export const auditApi = {
  list(workspaceId: string) {
    return apiClient
      .get<AuditLog[]>(`/workspaces/${workspaceId}/audit-logs`)
      .then((r) => r.data);
  },
};