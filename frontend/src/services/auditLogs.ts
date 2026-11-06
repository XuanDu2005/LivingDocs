import apiClient from './api';

export interface AuditLog {
  id: string;
  actorUserId: string | null;
  /**
   * The resolved display name of the actor, or `null` when the actor
   * could not be resolved (a deleted account, or a system action).
   *
   * <p>The backend resolves it: a reader of the trail has to know
   * <em>who</em> acted, and a bare UUID is not an answer.
   */
  actorName?: string | null;
  actorRole: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  workspaceId: string | null;
  /** The document the entry belongs to, when the action concerned one. */
  documentId?: string | null;
  /** JSON-encoded string from the backend; the UI parses it lazily when expanded. */
  payload: string;
  createdAt: string;
}

/**
 * One entry of a document's audit trail.
 *
 * <p>Unlike {@link AuditLog} this projection names the document the
 * entry belongs to, so the trail of a document can be rendered without
 * resolving anything on the client.
 */
export interface AuditTrailEntry {
  id: string;
  actorUserId: string | null;
  actorName: string | null;
  actorRole: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  workspaceId: string | null;
  documentId: string | null;
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
 * The paged envelope of a document's audit trail.
 *
 * <p>`totalPages` is exposed so the reader can walk back to the first
 * entry — the requirement is the *complete* trail, not only its most
 * recent page.
 */
export interface AuditTrailPage {
  documentId: string;
  items: AuditTrailEntry[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
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

/**
 * Fetch the complete audit trail of a single document.
 *
 * <p>The Manager role has to be able to "view the complete audit trail of
 * a document (AI-generated, auto-updated, staff-edited) before granting
 * approval". The workspace-wide trail cannot answer that — it mixes in
 * every other action of the workspace and its rows carry no link to the
 * document.
 *
 * <p>Requires the `AUDIT_LOG_VIEW` permission and workspace membership.
 */
export async function listDocumentAuditTrail(
  workspaceId: string,
  documentId: string,
  page = 0,
  size = 200,
): Promise<AuditTrailPage> {
  const { data } = await apiClient.get<AuditTrailPage>(
    `/workspaces/${workspaceId}/documents/${documentId}/audit-trail`,
    { params: { page, size } },
  );
  return data;
}
