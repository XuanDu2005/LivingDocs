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
};