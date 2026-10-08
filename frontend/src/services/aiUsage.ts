import apiClient from './api';

export interface AiUsageSummary {
  todayTokens: number;
  monthTokens: number;
  dailyLimit: number | null;
  monthlyLimit: number | null;
}

export interface UpdateLimitsPayload {
  dailyTokenLimit: number | null;
  monthlyTokenLimit: number | null;
  rateLimitPerMinute: number | null;
}

export const aiUsageApi = {
  getSummary: async (workspaceId: string): Promise<AiUsageSummary> => {
    const { data } = await apiClient.get<AiUsageSummary>(
      `/workspaces/${workspaceId}/ai-usage`
    );
    return data;
  },

  updateLimits: async (workspaceId: string, payload: UpdateLimitsPayload): Promise<AiUsageSummary> => {
    const { data } = await apiClient.put<AiUsageSummary>(
      `/workspaces/${workspaceId}/ai-usage/limits`,
      payload
    );
    return data;
  },
};
