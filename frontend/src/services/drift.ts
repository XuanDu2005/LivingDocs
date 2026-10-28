/**
 * Drift alerts + review API client.
 */
import apiClient from './api';

export type DriftKind = 'REFERENTIAL' | 'SIGNATURE' | 'SEMANTIC';
export type DriftSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type DriftResolution = 'OPEN' | 'ACCEPTED' | 'DISMISSED' | 'FIXED';
export type ReviewDecision = 'APPROVED' | 'REJECTED' | 'REQUEST_CHANGES' | 'COMMENT';

export interface DriftAlert {
  id: string;
  workspaceId: string;
  repositoryId: string;
  pullRequestId: string | null;
  documentId: string;
  driftKind: DriftKind;
  severity: DriftSeverity;
  title: string;
  description: string;
  evidenceJson: string;
  resolutionStatus: DriftResolution;
  aiSuggestion: string | null;
  confidenceScore: number | null;
  detectedAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
}

export interface DocumentReview {
  id: string;
  documentVersionId: string;
  reviewerUserId: string;
  reviewerRole: string;
  decision: ReviewDecision;
  comment: string | null;
  decidedAt: string;
}

/** One row in a review queue — a version waiting for a decision. */
export interface ReviewQueueItem {
  versionId: string;
  documentId: string;
  workspaceId: string;
  documentTitle: string;
  documentSlug: string;
  docType: string;
  versionNumber: number;
  versionStatus: string;
  confidenceScore: number | null;
  actorRole: string | null;
  changeSummary: string | null;
  action: 'STAFF_REVIEW' | 'MANAGER_APPROVAL' | 'PUBLISH';
  createdAt: string;
}

/** The caller's capabilities and queues in one workspace. */
export interface ReviewQueue {
  workspaceId: string;
  platformRoles: string[];
  member: boolean;
  workspaceManager: boolean;
  canStaffReview: boolean;
  canManagerApprove: boolean;
  staffQueue: ReviewQueueItem[];
  managerQueue: ReviewQueueItem[];
  publishQueue: ReviewQueueItem[];
}

/** The effective permissions of the current user in a workspace. */
export interface WorkspaceCapabilities {
  member: boolean;
  workspaceManager: boolean;
  canStaffReview: boolean;
  canManagerApprove: boolean;
  admin: boolean;
  platformRoles: string[];
}

export const driftApi = {
  list(
    workspaceId: string,
    filters?: {
      status?: DriftResolution;
      kind?: DriftKind;
      severity?: DriftSeverity;
    },
  ) {
    const qs = filters
      ? '?' +
        Object.entries(filters)
          .filter(([, v]) => v !== undefined && v !== null)
          .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
          .join('&')
      : '';
    return apiClient
      .get<DriftAlert[]>(`/workspaces/${workspaceId}/drift-alerts${qs}`)
      .then((r) => r.data);
  },
  get(workspaceId: string, alertId: string) {
    return apiClient
      .get<DriftAlert>(`/workspaces/${workspaceId}/drift-alerts/${alertId}`)
      .then((r) => r.data);
  },
  resolve(
    workspaceId: string,
    alertId: string,
    resolution: DriftResolution,
  ) {
    return apiClient
      .post<DriftAlert>(
        `/workspaces/${workspaceId}/drift-alerts/${alertId}/resolve`,
        { resolution },
      )
      .then((r) => r.data);
  },
  detect(
    workspaceId: string,
    payload: {
      repositoryId: string;
      pullRequestId?: string;
      documentId: string;
      filesBefore: { path: string; content: string }[];
      filesAfter: { path: string; content: string }[];
    },
  ) {
    return apiClient
      .post<{ ingested: number; alerts: DriftAlert[] }>(
        `/workspaces/${workspaceId}/drift-alerts/detect?repositoryId=${payload.repositoryId}${
          payload.pullRequestId ? `&pullRequestId=${payload.pullRequestId}` : ''
        }&documentId=${payload.documentId}`,
        {
          filesBefore: payload.filesBefore,
          filesAfter: payload.filesAfter,
        },
      )
      .then((r) => r.data);
  },
};

export const reviewsApi = {
  forVersion(
    workspaceId: string,
    documentId: string,
    versionId: string,
  ) {
    return apiClient
      .get<DocumentReview[]>(
        `/workspaces/${workspaceId}/documents/${documentId}/versions/${versionId}/reviews`,
      )
      .then((r) => r.data);
  },
  staffReview(
    workspaceId: string,
    documentId: string,
    versionId: string,
    decision: ReviewDecision,
    comment?: string,
  ) {
    return apiClient
      .post<DocumentReview>(
        `/workspaces/${workspaceId}/documents/${documentId}/versions/${versionId}/staff-review`,
        { decision, comment },
      )
      .then((r) => r.data);
  },
  managerDecision(
    workspaceId: string,
    documentId: string,
    versionId: string,
    decision: ReviewDecision,
    comment?: string,
  ) {
    return apiClient
      .post<DocumentReview>(
        `/workspaces/${workspaceId}/documents/${documentId}/versions/${versionId}/manager-decision`,
        { decision, comment },
      )
      .then((r) => r.data);
  },
  myReviews() {
    return apiClient
      .get<DocumentReview[]>('/me/reviews')
      .then((r) => r.data);
  },
  /**
   * Everything the caller may act on in this workspace, split by tier.
   *
   * <p>This is what the review queue page renders: the Staff queue, the
   * Manager queue and the publish queue. Buckets the caller has no
   * capability for come back empty rather than forbidden, so a single
   * request drives the whole page.
   */
  pendingOnMe(workspaceId: string) {
    return apiClient
      .get<ReviewQueue>(`/workspaces/${workspaceId}/reviews/pending-on-me`)
      .then((r) => r.data);
  },
  staffQueue(workspaceId: string) {
    return apiClient
      .get<ReviewQueueItem[]>(`/workspaces/${workspaceId}/reviews/staff-queue`)
      .then((r) => r.data);
  },
  managerQueue(workspaceId: string) {
    return apiClient
      .get<ReviewQueueItem[]>(`/workspaces/${workspaceId}/reviews/manager-queue`)
      .then((r) => r.data);
  },
  publishQueue(workspaceId: string) {
    return apiClient
      .get<ReviewQueueItem[]>(`/workspaces/${workspaceId}/reviews/publish-queue`)
      .then((r) => r.data);
  },
  /** The caller's effective capabilities in this workspace. */
  capabilities(workspaceId: string) {
    return apiClient
      .get<WorkspaceCapabilities>(`/workspaces/${workspaceId}/capabilities`)
      .then((r) => r.data);
  },
};