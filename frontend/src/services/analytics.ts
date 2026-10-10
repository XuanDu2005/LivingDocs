import apiClient from './api';

export interface DailyCount {
  date: string;
  count: number;
}

export interface AnalyticsResponse {
  days: number;
  startDate: string;
  endDate: string;
  documentGrowth: DailyCount[];
  activeUsers: DailyCount[];
  driftTrends: DailyCount[];
  indexingActivity: DailyCount[];
  documentStatusBreakdown: Record<string, number>;
  driftSeverityBreakdown: Record<string, number>;
}

export const analyticsApi = {
  get: async (workspaceId: string, days = 30): Promise<AnalyticsResponse> => {
    const { data } = await apiClient.get<AnalyticsResponse>(
      `/workspaces/${workspaceId}/analytics`,
      { params: { days } }
    );
    return data;
  },
};
