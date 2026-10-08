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
  unreadCount() {
    return apiClient
      .get<{ count: number }>('/notifications/unread-count')
      .then((r) => r.data.count);
  },
  markRead(id: string) {
    return apiClient
      .post<Notification>(`/notifications/${id}/read`)
      .then((r) => r.data);
  },
};

export type NotificationTarget = 'user' | 'role' | 'all';

export interface SendNotificationPayload {
  target: NotificationTarget;
  userId?: string;
  roleCode?: string;
  /** Free-form label so future in-app grouping can split by campaign. */
  kind: string;
  title: string;
  body?: string;
  link?: string;
}

export interface SendNotificationResponse {
  target: NotificationTarget;
  roleCode: string | null;
  kind: string;
  title: string;
  recipients: number;
  duplicatesDropped: number;
}

export interface BroadcastHistoryEntry {
  id: string;
  actorUserId: string | null;
  actorRole: string | null;
  sentAt: string;
  action: string;
  target: string;
  roleCode: string | null;
  kind: string;
  title: string;
  recipients: number;
  body: string | null;
  link: string | null;
}

export const adminNotificationsApi = {
  send(payload: SendNotificationPayload) {
    return apiClient
      .post<SendNotificationResponse>('/admin/notifications/send', payload)
      .then((r) => r.data);
  },
  history(limit = 50) {
    return apiClient
      .get<BroadcastHistoryEntry[]>(`/admin/notifications/history?limit=${limit}`)
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