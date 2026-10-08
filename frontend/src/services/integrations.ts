import apiClient from './api';
import {
  ConnectIntegrationPayload,
  Connection,
  EventRow,
  IntegrationProvider,
  TestIntegrationResult,
} from '../types/integrations';

/**
 * Client-side wrappers for the integrations module.
 *
 * Two scopes:
 * <li>Admin: manage every connection across the platform (all workspaces
 *     plus platform-wide Slack channels).</li>
 * <li>Workspace: list / link connections for a single workspace.
 *
 * The backend does not echo stored tokens back; the corresponding
 * {@code hasAccessToken} / {@code hasWebhookSecret} flags indicate whether
 * one is configured.
 */
export const integrationsApi = {
  // ---- Admin --------------------------------------------------------

  async listAdminConnections(provider?: IntegrationProvider): Promise<Connection[]> {
    const { data } = await apiClient.get<Connection[]>('/admin/integrations', {
      params: provider ? { provider } : {},
    });
    return data;
  },

  async getAdminConnection(id: string): Promise<Connection> {
    const { data } = await apiClient.get<Connection>(`/admin/integrations/${id}`);
    return data;
  },

  async createAdminConnection(payload: ConnectIntegrationPayload): Promise<Connection> {
    const { data } = await apiClient.post<Connection>('/admin/integrations', payload);
    return data;
  },

  async updateAdminConnection(
    id: string,
    payload: ConnectIntegrationPayload,
  ): Promise<Connection> {
    const { data } = await apiClient.put<Connection>(
      `/admin/integrations/${id}`,
      payload,
    );
    return data;
  },

  async revokeAdminConnection(id: string): Promise<void> {
    await apiClient.delete(`/admin/integrations/${id}`);
  },

  async testAdminConnection(id: string): Promise<TestIntegrationResult> {
    const { data } = await apiClient.post<TestIntegrationResult>(
      `/admin/integrations/${id}/test`,
      {},
    );
    return data;
  },

  async listAdminEvents(limit = 50): Promise<EventRow[]> {
    const { data } = await apiClient.get<EventRow[]>('/admin/integrations/events', {
      params: { limit },
    });
    return data;
  },

  async listConnectionEvents(id: string, limit = 50): Promise<EventRow[]> {
    const { data } = await apiClient.get<EventRow[]>(
      `/admin/integrations/${id}/events`,
      { params: { limit } },
    );
    return data;
  },

  // ---- Workspace ----------------------------------------------------

  async listWorkspaceConnections(
    workspaceId: string,
    provider?: IntegrationProvider,
  ): Promise<Connection[]> {
    const { data } = await apiClient.get<Connection[]>(
      `/workspaces/${workspaceId}/integrations`,
      { params: provider ? { provider } : {} },
    );
    return data;
  },

  async createWorkspaceConnection(
    workspaceId: string,
    payload: ConnectIntegrationPayload,
  ): Promise<Connection> {
    const { data } = await apiClient.post<Connection>(
      `/workspaces/${workspaceId}/integrations`,
      payload,
    );
    return data;
  },
};