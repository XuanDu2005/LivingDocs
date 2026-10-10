/**
 * Platform-wide analytics API.
 */
import apiClient from './api';

export interface DailyCount {
  date: string;
  count: number;
}

export interface AdminAnalytics {
  days: number;
  startDate: string;
  endDate: string;
  totalUsers: number;
  totalEnabledUsers: number;
  totalWorkspaces: number;
  totalDocuments: number;
  totalDriftAlerts: number;
  openDriftAlerts: number;
  totalIndexJobs: number;
  failedIndexJobs: number;
  totalAuditEvents: number;
  documentGrowth: DailyCount[];
  indexingActivity: DailyCount[];
  activeUsers: DailyCount[];
  documentStatusBreakdown: Record<string, number>;
  driftSeverityBreakdown: Record<string, number>;
}

export const adminAnalyticsApi = {
  get: async (days: number = 30): Promise<AdminAnalytics> => {
    const { data } = await apiClient.get<AdminAnalytics>('/admin/analytics', {
      params: { days },
    });
    return data;
  },
};
