/**
 * AI-assisted documentation flows (generate / drift detect).
 */
import apiClient from './api';

export interface GenerationJobStatus {
  id: string;
  documentId: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'NONE';
  template: string | null;
  documentVersionId: string | null;
  promptTokens: number;
  completionTokens: number;
  createdAt: string;
  completedAt: string | null;
  errorMessage?: string | null;
  message?: string;
}

export const aiApi = {
  /**
   * Queue an AI generation run.
   *
   * <p>The call is asynchronous: the backend returns 202 immediately with
   * a job id, and the LLM work happens on a Kafka worker. Poll
   * {@link generationStatus} to learn when the version is ready.
   */
  generate(
    workspaceId: string,
    documentId: string,
    files: { path: string; content: string }[],
    template = 'MODULE_GUIDE',
    focus?: string,
  ) {
    const qs = new URLSearchParams({ template });
    if (focus) qs.set('focus', focus);
    return apiClient
      .post<{
        status: string;
        jobId: string;
        documentId: string;
        message: string;
      }>(
        `/workspaces/${workspaceId}/documents/${documentId}/generate?${qs.toString()}`,
        files,
      )
      .then((r) => r.data);
  },

  /** Poll the state of the most recent generation run for a document. */
  generationStatus(workspaceId: string, documentId: string) {
    return apiClient
      .get<GenerationJobStatus>(
        `/workspaces/${workspaceId}/documents/${documentId}/generation-status`,
      )
      .then((r) => r.data);
  },

  /** Manually trigger a regeneration (also asynchronous). */
  regenerate(workspaceId: string, documentId: string) {
    return apiClient
      .post<{ status: string; jobId: string; documentId: string; message: string }>(
        `/workspaces/${workspaceId}/documents/${documentId}/regenerate`,
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

  /**
   * Generate documentation for all undocumented code entities in a repository.
   * Creates new documents or updates existing ones.
   */
  generateAllForRepository(
    workspaceId: string,
    repositoryId: string,
    template = 'MODULE_GUIDE',
  ) {
    return apiClient
      .post<{
        generated: number;
        skipped: number;
        errors: string[];
        documents: Array<{
          documentId: string;
          title: string;
          entity: string;
          versionNumber: number;
        }>;
      }>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/generate-all?template=${template}`,
        {},
      )
      .then((r) => r.data);
  },
};