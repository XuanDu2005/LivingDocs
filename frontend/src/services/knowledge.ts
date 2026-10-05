/**
 * Knowledge-base API client.
 */
import apiClient from './api';

export interface KnowledgeHit {
  doc_id: string;
  text: string;
  score: number;
  metadata: Record<string, unknown>;
}

export const knowledgeApi = {
  index(workspaceId: string, documentId: string) {
    return apiClient
      .post<{ chunks_indexed: number; status: string }>(
        `/workspaces/${workspaceId}/documents/${documentId}/knowledge/index`,
      )
      .then((r) => r.data);
  },
  remove(workspaceId: string, documentId: string) {
    return apiClient
      .delete<{ status: string }>(
        `/workspaces/${workspaceId}/documents/${documentId}/knowledge/index`,
      )
      .then((r) => r.data);
  },
  search(workspaceId: string, query: string, topK = 5) {
    return apiClient
      .post<{ hits: KnowledgeHit[]; status?: string }>(
        `/workspaces/${workspaceId}/knowledge/search`,
        { query, topK },
      )
      .then((r) => r.data);
  },
};

export interface CodeEntity {
  id: string;
  repositoryId: string;
  commitSha: string;
  language: string;
  entityType: string;
  qualifiedName: string;
  simpleName: string;
  filePath: string;
  startLine: number;
  endLine: number;
  signature: string | null;
  docstring: string | null;
  metadata: string | null;
  ingestedAt: string;
}

export const codeApi = {
  listEntities(workspaceId: string, repositoryId: string) {
    return apiClient
      .get<CodeEntity[]>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/code-entities`,
      )
      .then((r) => r.data);
  },
  getEntity(entityId: string) {
    return apiClient
      .get<CodeEntity>(`/code-entities/${entityId}`)
      .then((r) => r.data);
  },
};

export interface CodeDocumentLink {
  id: string;
  documentId: string;
  codeEntityId: string;
  linkKind: string;
  confidence: number | null;
  createdAt: string;
}

export const linksApi = {
  list(workspaceId: string, documentId: string) {
    return apiClient
      .get<CodeDocumentLink[]>(
        `/workspaces/${workspaceId}/documents/${documentId}/links`,
      )
      .then((r) => r.data);
  },
  link(
    workspaceId: string,
    documentId: string,
    codeEntityId: string,
    linkKind = 'REFERENCE',
    confidence?: number,
  ) {
    return apiClient
      .post<CodeDocumentLink>(
        `/workspaces/${workspaceId}/documents/${documentId}/links?codeEntityId=${codeEntityId}&linkKind=${linkKind}${
          confidence !== undefined ? `&confidence=${confidence}` : ''
        }`,
      )
      .then((r) => r.data);
  },
  clear(workspaceId: string, documentId: string) {
    return apiClient.delete<void>(
      `/workspaces/${workspaceId}/documents/${documentId}/links`,
    );
  },
};