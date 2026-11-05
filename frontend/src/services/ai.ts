import apiClient, { LONG_TIMEOUT_MS } from './api';

/**
 * One piece of grounding evidence: the code entity the AI relied on
 * when it wrote the version.
 *
 * <p>Mirrors the backend's `AiDtos.GroundingSource`.
 */
export interface GroundingSource {
  qualifiedName: string;
  kind: string;
  file: string;
  lines: number[];
  signature: string;
}

/**
 * The wire format the grounding endpoints answer with.
 *
 * <p>The AI service names the entity `qualified_name`, and the backend
 * stores that JSON verbatim in `document_versions.grounding_json`, so
 * the endpoints hand it back unchanged — in snake_case.
 */
interface GroundingSourceWire {
  qualified_name?: string;
  qualifiedName?: string;
  kind?: string;
  file?: string;
  lines?: number[];
  signature?: string;
}

/**
 * Normalise a grounding source into the camelCase shape the UI uses.
 *
 * <p>Both spellings are accepted, because the preview endpoint answers
 * with camelCase (the backend maps the record itself) while the stored
 * grounding comes back in the snake_case the AI service produced.
 */
function toGroundingSource(raw: GroundingSourceWire): GroundingSource {
  return {
    qualifiedName: raw.qualified_name ?? raw.qualifiedName ?? '',
    kind: raw.kind ?? '',
    file: raw.file ?? '',
    lines: Array.isArray(raw.lines) ? raw.lines : [],
    signature: raw.signature ?? '',
  };
}

/**
 * AI-assisted documentation flows (generate / drift detect).
 */
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

/**
 * The state of a bulk "generate all docs" run.
 */
export interface BulkGenerationJobStatus {
  jobId: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED';
  repositoryId: string;
  template: string | null;
  totalEntities: number;
  processedEntities: number;
  generated: number;
  skipped: number;
  failed: number;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  errors?: string[];
  documents?: Array<{
    documentId: string;
    title: string;
    entity: string;
    versionNumber: number;
  }>;
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

  /**
   * Manually trigger a regeneration of a document.
   *
   * <p>The source is resolved server-side from the document's linked code
   * entities (the real implementation, pulled from GitHub) plus the
   * current body, so the AI has something to work from.
   */
  regenerate(workspaceId: string, documentId: string) {
    return apiClient
      .post<{
        status: string;
        jobId: string;
        documentId: string;
        message: string;
      }>(
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
        { timeout: LONG_TIMEOUT_MS },
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
      .get<{ sources: GroundingSourceWire[] }>(
        `/workspaces/${workspaceId}/documents/${documentId}/versions/${versionId}/grounding`,
      )
      .then((r) => ({
        sources: (r.data?.sources ?? []).map(toGroundingSource),
      }));
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
        sources: GroundingSourceWire[];
        template: string;
        generatedAt: string;
      }>(
        `/workspaces/${workspaceId}/documents/${documentId}/preview-generate?template=${template}`,
        files,
        { timeout: LONG_TIMEOUT_MS },
      )
      .then((r) => ({
        ...r.data,
        sources: (r.data?.sources ?? []).map(toGroundingSource),
      }));
  },

  /**
   * Queue a bulk documentation generation run for a repository.
   *
   * <p>This is asynchronous: the backend answers 202 with a job id and
   * the actual LLM work happens on a background worker. Poll
   * {@link bulkGenerationStatus} to follow the progress.
   */
  generateAllForRepository(
    workspaceId: string,
    repositoryId: string,
    template = 'MODULE_GUIDE',
  ) {
    return apiClient
      .post<{
        status: string;
        jobId: string;
        repositoryId: string;
        totalEntities: number;
        message: string;
      }>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/generate-all?template=${template}`,
        {},
      )
      .then((r) => r.data);
  },

  /**
   * Poll the progress of a bulk generation run.
   */
  bulkGenerationStatus(workspaceId: string, jobId: string) {
    return apiClient
      .get<BulkGenerationJobStatus>(
        `/workspaces/${workspaceId}/bulk-generation-jobs/${jobId}`,
      )
      .then((r) => r.data);
  },

  /**
   * List the recent bulk generation runs of a workspace.
   */
  bulkGenerationJobs(workspaceId: string) {
    return apiClient
      .get<BulkGenerationJobStatus[]>(
        `/workspaces/${workspaceId}/bulk-generation-jobs`,
      )
      .then((r) => r.data);
  },
};