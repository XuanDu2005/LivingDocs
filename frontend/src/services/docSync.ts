/**
 * Doc Sync API client - scan and sync documentation files with code entities.
 */
import apiClient, { LONG_TIMEOUT_MS } from './api';
import type { DocUpdateRequest } from './docUpdateRequests';

export interface DocFileInfo {
  path: string;
  type: string;
  size: number;
  referencedEntities: number;
  foundEntities: string[];
  missingEntities: string[];
  hasDrift: boolean;
  driftReason: string;
}

export interface DriftFinding {
  driftKind: string;
  severity: string;
  title: string;
  description: string;
  suggestion: string;
}

export interface DocSyncPreview {
  filePath: string;
  currentContent: string;
  proposedContent: string | null;
  diff: string | null;
  findings: DriftFinding[];
  noEntitiesFound: boolean;
}

export interface ApplyResult {
  success: boolean;
  prUrl: string;
  message: string;
}

export interface DocContent {
  path: string;
  content: string;
}

/**
 * The progress of a repository-wide documentation sync.
 *
 * <p>The sync asks the AI to propose an update for every drifted
 * file, which costs one LLM call per file — far too slow to run
 * inside an HTTP request. The endpoint therefore answers 202 with a
 * job id, and the client polls {@link docSyncApi.jobStatus} until the
 * job reaches a terminal state.
 */
export interface DocSyncJobStatus {
  jobId: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED';
  repositoryId: string;
  branch?: string | null;
  /** How many Markdown files the scan found. */
  totalFiles: number;
  /** How many drifted files have been processed. */
  scannedFiles: number;
  /** How many files turned out to have drifted. */
  driftedFiles: number;
  /** How many review proposals were created. */
  queuedProposals: number;
  /** How many drifted files were not queued. */
  skippedProposals: number;
  /** How many files could not be processed. */
  failedFiles: number;
  errorMessage?: string | null;
  /** The per-file outcome of the run. */
  files?: Array<{
    path: string;
    status: 'QUEUED' | 'SKIPPED' | 'FAILED';
    message?: string;
  }>;
}

export const docSyncApi = {
  /**
   * Queue a repository-wide documentation sync.
   *
   * <p>The server walks the repository's Markdown files, compares each
   * against the indexed code, and asks the AI to propose an update for
   * every file that drifted. That is one LLM call per drifted file, so
   * the work runs on a background worker and the endpoint answers 202.
   */
  scan(workspaceId: string, repositoryId: string, branch?: string) {
    const qs = branch ? `?branch=${encodeURIComponent(branch)}` : '';
    return apiClient
      .post<{
        status: string;
        jobId: string;
        repositoryId: string;
        message: string;
      }>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/scan-docs${qs}`,
        {},
      )
    .then((r) => r.data);
  },

  /**
   * Poll the progress of a documentation sync.
   */
  jobStatus(workspaceId: string, jobId: string) {
    return apiClient
      .get<DocSyncJobStatus>(`/workspaces/${workspaceId}/doc-sync-jobs/${jobId}`)
      .then((r) => r.data);
  },

  /**
   * List the recent documentation syncs of a workspace.
   */
  jobs(workspaceId: string) {
    return apiClient
      .get<DocSyncJobStatus[]>(`/workspaces/${workspaceId}/doc-sync-jobs`)
      .then((r) => r.data);
  },

  /**
   * Preview documentation sync - generates diff between current and proposed content.
   *
   * Performs one AI call, so it needs the long timeout too.
   */
  preview(
    workspaceId: string,
    repositoryId: string,
    filePath: string,
    branch?: string,
  ) {
    return apiClient
      .post<DocSyncPreview>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-sync/preview`,
        { filePath, branch },
        { timeout: LONG_TIMEOUT_MS },
      )
    .then((r) => r.data);
  },

  /**
   * Apply documentation sync - creates a PR with the updated content.
   */
  apply(
    workspaceId: string,
    repositoryId: string,
    filePath: string,
    proposedContent: string,
    branch?: string,
  ) {
    return apiClient
      .post<ApplyResult>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-sync/apply`,
        { filePath, proposedContent, branch },
        { timeout: LONG_TIMEOUT_MS },
      )
    .then((r) => r.data);
  },

  /**
   * Get content of a specific documentation file.
   */
  getContent(
    workspaceId: string,
    repositoryId: string,
    filePath: string,
    branch?: string,
  ) {
    const params = new URLSearchParams();
    if (branch) params.set('branch', branch);
    const qs = params.toString() ? `?${params.toString()}` : '';
    return apiClient
      .get<DocContent>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/docs/${filePath}${qs}`,
      )
      .then((r) => r.data);
  },
};

/**
 * Queue a doc update for review (creates a PENDING_REVIEW DocUpdateRequest
 * instead of opening a PR directly). Returns the freshly created request.
 */
export interface DocSyncQueueResult {
  success: boolean;
  /** Whether a proposal was actually created. */
  queued: boolean;
  /** The created proposal; absent when nothing was queued. */
  request?: DocUpdateRequest | null;
  message?: string;
}

export const docSyncQueueApi = {
  queue(
    workspaceId: string,
    repositoryId: string,
    filePath: string,
    branch?: string,
  ) {
    return apiClient
      .post<DocSyncQueueResult>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-sync/queue`,
        { filePath, branch },
        { timeout: LONG_TIMEOUT_MS },
      )
    .then((r) => r.data);
  },
};
