import apiClient from './api';

export interface AuditLog {
  id: string;
  actorUserId: string | null;
  actorRole: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  workspaceId: string | null;
  /** JSON-encoded string from the backend; the UI parses it lazily when expanded. */
  payload: string;
  createdAt: string;
}

/** Fetch the audit log for a workspace. Requires MANAGER on the workspace. */
export async function listAuditLogs(workspaceId: string): Promise<AuditLog[]> {
  const { data } = await apiClient.get<AuditLog[]>(
    `/workspaces/${workspaceId}/audit-logs`,
  );
  return data;
}