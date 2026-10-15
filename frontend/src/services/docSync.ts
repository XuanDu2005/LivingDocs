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
}

export interface DocScanResult {
  totalFiles: number;
  referencedEntities: number;
  updatedEntities: number;
  files: DocFileInfo[];
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
      .get<DocScanResult>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/scan-docs${qs}`,
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
