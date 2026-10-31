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

/** Paged envelope returned by the audit-log endpoints. */
export interface AuditLogPage {
  items: AuditLog[];
  page: number;
  size: number;
  total: number;
}

/**
 * Fetch the audit log for a workspace. Requires MANAGER on the workspace.
 *
 * The backend returns a paged envelope; this helper unwraps it so callers
 * keep working with a plain list.
 */
export async function listAuditLogs(
  workspaceId: string,
  page = 0,
  size = 200,
): Promise<AuditLog[]> {
  const { data } = await apiClient.get<AuditLogPage | AuditLog[]>(
    `/workspaces/${workspaceId}/audit-logs`,
    { params: { page, size } },
  );
  if (Array.isArray(data)) return data;
  return data.items ?? [];
}
