/**
 * AI-assisted documentation flows (generate / drift detect).
 */
import apiClient from './api';

export const aiApi = {
  generate(
    workspaceId: string,
    documentId: string,
    files: { path: string; content: string }[],
    template = 'MODULE_GUIDE',
  ) {
    return apiClient
      .post<{
        id: string;
        documentId: string;
        versionNumber: number;
        bodyMarkdown: string;
        actorRole: string;
        confidenceScore: number | null;
        status: string;
      }>(
        `/workspaces/${workspaceId}/documents/${documentId}/generate?template=${template}`,
        files,
      )
      .then((r) => r.data);
  },
  detectDrift(
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
      .post<{ ingested: number; alerts: unknown[] }>(
        `/workspaces/${workspaceId}/drift-alerts/detect?repositoryId=${payload.repositoryId}${
          payload.pullRequestId ? `&pullRequestId=${payload.pullRequestId}` : ''
        }&documentId=${payload.documentId}`,
        { filesBefore: payload.filesBefore, filesAfter: payload.filesAfter },
      )
      .then((r) => r.data);
  },
  /** Fetch the grounding evidence (source code references) for a specific AI-generated version. */
  fetchGrounding(
    workspaceId: string,
    documentId: string,
    versionId: string,
  ) {
    return apiClient
      .get<{ sources: { qualifiedName: string; kind: string; file: string; lines: number[]; signature: string }[] }>(
        `/workspaces/${workspaceId}/documents/${documentId}/versions/${versionId}/grounding`,
      )
      .then((r) => r.data);
  },
  /** Preview an AI generation without persisting a new version. */
  previewGenerate(
    workspaceId: string,
    documentId: string,
    files: { path: string; content: string }[],
    template = 'MODULE_GUIDE',
  ) {
    return apiClient
      .post<{
        markdown: string;
        confidenceScore: number;
        sources: { qualifiedName: string; kind: string; file: string; lines: number[]; signature: string }[];
        template: string;
        generatedAt: string;
      }>(
        `/workspaces/${workspaceId}/documents/${documentId}/preview-generate?template=${template}`,
        files,
      )
      .then((r) => r.data);
  },
};