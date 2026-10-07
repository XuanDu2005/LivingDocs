import apiClient from './api';
import { AiSettings, UpdateAiSettingsPayload } from '../types/aiSettings';

export const aiSettingsApi = {
  get: async (workspaceId: string): Promise<AiSettings> => {
    const { data } = await apiClient.get<AiSettings>(`/workspaces/${workspaceId}/ai-settings`);
    return data;
  },

  update: async (workspaceId: string, payload: UpdateAiSettingsPayload): Promise<AiSettings> => {
    const { data } = await apiClient.put<AiSettings>(
      `/workspaces/${workspaceId}/ai-settings`,
      payload,
    );
    return data;
  },

  test: async (workspaceId: string): Promise<{ status: string; provider: string; model: string; hasApiKey: boolean }> => {
    const { data } = await apiClient.get<{ status: string; provider: string; model: string; hasApiKey: boolean }>(
      `/workspaces/${workspaceId}/ai-settings/test`,
    );
    return data;
  },
};
