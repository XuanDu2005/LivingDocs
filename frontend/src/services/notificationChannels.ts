import apiClient from './api';

export interface NotificationChannelConfig {
  id: string;
  workspaceId: string | null;
  eventKind: string;
  inAppEnabled: boolean;
  emailEnabled: boolean;
  emailRecipients: string | null;
  updatedAt: string;
}

export interface UpdateChannelPayload {
  eventKind: string;
  inAppEnabled: boolean;
  emailEnabled: boolean;
  emailRecipients: string | null;
}

export const notificationChannelsApi = {
  list: async (workspaceId: string): Promise<NotificationChannelConfig[]> => {
    const { data } = await apiClient.get<NotificationChannelConfig[]>(
      `/workspaces/${workspaceId}/notification-channels`
    );
    return data;
  },

  update: async (workspaceId: string, payload: UpdateChannelPayload): Promise<NotificationChannelConfig> => {
    const { data } = await apiClient.put<NotificationChannelConfig>(
      `/workspaces/${workspaceId}/notification-channels`,
      payload
    );
    return data;
  },
};
