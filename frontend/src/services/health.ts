import apiClient from './api';

export interface HealthStatus {
  status: string;
  service: string;
}

/**
 * Hit the backend health endpoint. Returns the parsed payload on success
 * and throws on failure so the caller can render appropriate UI.
 */
export async function fetchBackendHealth(): Promise<HealthStatus> {
  const { data } = await apiClient.get<HealthStatus>('/health');
  return data;
}