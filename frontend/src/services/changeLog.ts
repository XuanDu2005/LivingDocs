/**
 * Workspace-level change log API client.
 *
 * <p>The change log is the workspace-wide view of the version history:
 * every change of every document, filterable by the role that produced
 * it, by document, by repository and by date range. The per-document
 * timeline answers a different question — "what happened to this
 * document" — and neither view can serve the other.
 */
import apiClient from './api';

export type ChangeLogActorRole = 'AI' | 'STAFF' | 'DEVELOPER' | 'MANAGER' | 'SYSTEM';

export type ChangeLogSort = 'CREATED_AT' | 'ACTOR_ROLE' | 'VERSION_NUMBER';

export type ChangeLogDirection = 'ASC' | 'DESC';

export type ChangeLogStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'IN_REVIEW'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'SUPERSEDED';

/** One row of the change log. */
export interface ChangeLogEntry {
  id: string;
  documentId: string;
  /** The title of the document the change belongs to. */
  documentTitle: string | null;
  documentSlug: string | null;
  /** The repository the document is linked to, when it has one. */
  repositoryId: string | null;
  versionNumber: number;
  actorRole: ChangeLogActorRole;
  actorUserId: string | null;
  /**
   * The resolved display name of the actor.
   *
   * <p>Resolved by the backend: a change log that only carries a UUID
   * does not answer "who changed this?".
   */
  actorName: string | null;
  changeSummary: string | null;
  status: ChangeLogStatus;
  sourceCommitSha: string | null;
  sourcePrId: string | null;
  /** The ticket the change belongs to, e.g. `PROJ-123`. */
  sourceTicketRef: string | null;
  confidenceScore: number | null;
  createdAt: string;
}

/** The filters of the change log. Every axis is optional. */
export interface ChangeLogFilters {
  documentId?: string | null;
  repositoryId?: string | null;
  actorRole?: ChangeLogActorRole | null;
  /** ISO-8601 instant, e.g. `2026-01-01T00:00:00Z`. */
  from?: string | null;
  to?: string | null;
  sort?: ChangeLogSort | null;
  direction?: ChangeLogDirection | null;
}

/** The paged envelope returned by the change-log endpoint. */
export interface ChangeLogPage {
  items: ChangeLogEntry[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

/**
 * Fetch a page of the change log of a workspace.
 *
 * <p>Requires the `AUDIT_LOG_VIEW` permission and workspace
 * membership.
 */
export async function listChangeLog(
  workspaceId: string,
  filters: ChangeLogFilters = {},
  page = 0,
  size = 50,
): Promise<ChangeLogPage> {
  const params: Record<string, string | number> = { page, size };
  if (filters.documentId) params.documentId = filters.documentId;
  if (filters.repositoryId) params.repositoryId = filters.repositoryId;
  if (filters.actorRole) params.actorRole = filters.actorRole;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  if (filters.sort) params.sort = filters.sort;
  if (filters.direction) params.direction = filters.direction;

  const { data } = await apiClient.get<ChangeLogPage>(
    `/workspaces/${workspaceId}/change-log`,
    { params },
  );
  return data;
}

export const changeLogApi = {
  list: listChangeLog,
};
