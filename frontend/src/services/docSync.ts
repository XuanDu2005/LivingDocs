/**
 * Doc Sync API client - scan and sync documentation files with code entities.
 */
import apiClient from './api';

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

export interface DocScanResult {
  totalFiles: number;
  referencedEntities: number;
  updatedEntities: number;
  driftCount: number;
  /** Number of new review proposals created by this scan. */
  queuedCount?: number;
  /** Drifted files that were not queued (already pending, or no real change). */
  skippedCount?: number;
  /** Paths of the files that were queued for review. */
  queuedFiles?: string[];
  files: DocFileInfo[];
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

export const docSyncApi = {
  /**
   * Scan repository for documentation files and code references.
   */
  scan(workspaceId: string, repositoryId: string, branch?: string) {
    const qs = branch ? `?branch=${encodeURIComponent(branch)}` : '';
    return apiClient
      .post<DocScanResult>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/scan-docs${qs}`,
        {},
      )
      .then((r) => r.data);
  },

  /**
   * Preview documentation sync - generates diff between current and proposed content.
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
  id: string;
  status: string;
  filePath: string;
  hasChanges: boolean;
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
      )
      .then((r) => r.data);
  },
};
