/**
 * Documents + version history API client.
 */
import apiClient from './api';

export type DocumentStatus =
  | 'DRAFT'
  | 'IN_REVIEW'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'ARCHIVED';

export interface Document {
  id: string;
  workspaceId: string;
  repositoryId: string | null;
  templateId: string | null;
  headVersionId: string | null;
  title: string;
  slug: string;
  docType: string;
  summary: string | null;
  status: DocumentStatus;
  autoUpdateEnabled: boolean;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
}

export type ActorRole = 'AI' | 'STAFF' | 'MANAGER' | 'SYSTEM';
export type VersionStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'IN_REVIEW'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'SUPERSEDED';

export interface DocumentVersion {
  id: string;
  documentId: string;
  versionNumber: number;
  bodyMarkdown: string;
  changeSummary: string | null;
  actorRole: ActorRole;
  actorUserId: string | null;
  sourceCommitSha: string | null;
  sourcePrId: string | null;
  templateId: string | null;
  status: VersionStatus;
  confidenceScore: number | null;
  createdAt: string;
  triggerCommit?: TriggerCommitSummary | null;
}

export interface TriggerCommitSummary {
  id: string | null;
  sha: string;
  shortSha: string;
  message: string | null;
  authorName: string | null;
  authoredAt: string | null;
  url: string | null;
}

export interface DocumentVersionSummary {
  id: string;
  versionNumber: number;
  changeSummary: string | null;
  actorRole: ActorRole;
  actorUserId: string | null;
  sourceCommitSha: string | null;
  sourceCommitShort: string | null;
  sourcePrId: string | null;
  status: VersionStatus;
  confidenceScore: number | null;
  createdAt: string;
}

export const documentsApi = {
  list(workspaceId: string, docType?: string) {
    const qs = docType ? `?docType=${encodeURIComponent(docType)}` : '';
    return apiClient
      .get<Document[]>(`/workspaces/${workspaceId}/documents${qs}`)
      .then((r) => r.data);
  },
  get(workspaceId: string, documentId: string) {
    return apiClient
      .get<Document>(`/workspaces/${workspaceId}/documents/${documentId}`)
      .then((r) => r.data);
  },
  create(
    workspaceId: string,
    payload: {
      repositoryId?: string;
      templateId?: string;
      title: string;
      slug: string;
      docType: string;
      summary?: string;
      autoUpdateEnabled: boolean;
      initialBody: string;
    },
  ) {
    return apiClient
      .post<Document>(`/workspaces/${workspaceId}/documents`, payload)
      .then((r) => r.data);
  },
  update(
    workspaceId: string,
    documentId: string,
    payload: {
      title: string;
      summary?: string;
      repositoryId?: string;
      templateId?: string;
      autoUpdateEnabled: boolean;
    },
  ) {
    return apiClient
      .put<Document>(
        `/workspaces/${workspaceId}/documents/${documentId}`,
        payload,
      )
      .then((r) => r.data);
  },
  remove(workspaceId: string, documentId: string) {
    return apiClient.delete<void>(
      `/workspaces/${workspaceId}/documents/${documentId}`,
    );
  },
  timeline(documentId: string) {
    return apiClient
      .get<DocumentVersionSummary[]>(`/documents/${documentId}/versions`)
      .then((r) => r.data);
  },
  version(documentId: string, versionNumber: number) {
    return apiClient
      .get<DocumentVersion>(
        `/documents/${documentId}/versions/${versionNumber}`,
      )
      .then((r) => r.data);
  },
  diff(documentId: string, from: number, to: number) {
    return apiClient
      .get<{ unifiedDiff: string }>(
        `/documents/${documentId}/diff?from=${from}&to=${to}`,
      )
      .then((r) => r.data);
  },
  /** Shortcut: compare the most recent AI-authored version against the most recent published version. */
  diffAgainstPublished(documentId: string) {
    return apiClient
      .get<{ unifiedDiff: string }>(
        `/documents/${documentId}/diff?comparedTo=published`,
      )
      .then((r) => r.data);
  },
  triggerCommit(documentId: string, versionNumber: number) {
    return apiClient
      .get<TriggerCommitSummary | null>(
        `/documents/${documentId}/versions/${versionNumber}/trigger-commit`,
      )
      .then((r) => r.data);
  },
  appendVersion(
    workspaceId: string,
    documentId: string,
    payload: { bodyMarkdown: string; changeSummary?: string },
  ) {
    return apiClient
      .post<DocumentVersion>(
        `/workspaces/${workspaceId}/documents/${documentId}/versions`,
        payload,
      )
      .then((r) => r.data);
  },
  publishVersion(_workspaceId: string, documentId: string, versionNumber: number) {
    return apiClient.post<void>(
      `/documents/${documentId}/versions/${versionNumber}/publish`,
    );
  },
  /**
   * Push a draft version into the Staff review queue.
   *
   * Versions are created as drafts; only an explicit submit puts them in
   * front of a reviewer.
   */
  submitForReview(workspaceId: string, documentId: string, versionId: string) {
    return apiClient
      .post<{
        documentId: string;
        versionId: string;
        versionNumber: number;
        status: string;
        message: string;
      }>(
        `/workspaces/${workspaceId}/documents/${documentId}/versions/${versionId}/submit-for-review`,
      )
      .then((r) => r.data);
  },
  rollback(_workspaceId: string, documentId: string, version: number) {
    return apiClient
      .post<DocumentVersion>(
        `/documents/${documentId}/rollback?version=${version}`,
      )
      .then((r) => r.data);
  },
  toggleAutoUpdate: async (workspaceId: string, documentId: string, enabled: boolean) => {
    const { data } = await apiClient.put<Document>(
      `/workspaces/${workspaceId}/documents/${documentId}/auto-update?enabled=${enabled}`
    );
    return data;
  },
};