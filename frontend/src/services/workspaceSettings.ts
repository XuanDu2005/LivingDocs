import apiClient from './api';

export interface WorkspaceSettings {
  workspaceId: string;
  driftSeverityThreshold: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  autoUpdateOnPr: boolean;
  autoUpdateOnCommit: boolean;
  requireManagerApproval: boolean;
  mergePolicyCritical: 'WARN' | 'BLOCK';
  aiConfidenceThreshold: number;
  updatedAt: string;
}

export interface UpdateWorkspaceSettingsPayload {
  driftSeverityThreshold: string;
  autoUpdateOnPr: boolean;
  autoUpdateOnCommit: boolean;
  requireManagerApproval: boolean;
  mergePolicyCritical: string;
  aiConfidenceThreshold: number;
}

export async function getWorkspaceSettings(workspaceId: string): Promise<WorkspaceSettings> {
  const { data } = await apiClient.get<WorkspaceSettings>(
    `/workspaces/${workspaceId}/settings`
  );
  return data;
}

export async function updateWorkspaceSettings(
  workspaceId: string,
  payload: UpdateWorkspaceSettingsPayload
): Promise<WorkspaceSettings> {
  const { data } = await apiClient.put<WorkspaceSettings>(
    `/workspaces/${workspaceId}/settings`,
    payload
  );
  return data;
}
