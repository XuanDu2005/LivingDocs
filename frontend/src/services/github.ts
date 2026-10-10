import apiClient from './api';

/**
 * Client-side wrappers for the GitHub integration backend.
 *
 * The flow is:
 * 1. {@link getAuthorizeUrl} — fetch the GitHub authorize URL and CSRF state.
 * 2. The browser navigates to that URL. After consent, GitHub redirects
 *    back to the SPA with `?code=…&state=…`.
 * 3. {@link completeCallback} POSTs the code/state to the backend, which
 *    exchanges it for an access token and stores the new connection.
 */

export interface GithubAuthorizeResponse {
  url: string;
  state: string;
}

export interface GithubConnection {
  id: string;
  githubLogin: string;
  githubUserId: number;
  scope: string;
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED';
  connectedAt: string;
  lastUsedAt: string | null;
  lastSyncedAt: string | null;
}

export interface GithubRepositoryCatalogItem {
  githubId: number;
  name: string;
  fullName: string;
  owner: string;
  defaultBranch: string;
  htmlUrl: string | null;
  description: string | null;
  isPrivate: boolean;
  isArchived: boolean;
  isDisabled: boolean;
}

export interface Repository {
  id: string;
  workspaceId: string;
  githubId: number;
  owner: string;
  name: string;
  fullName: string;
  defaultBranch: string;
  htmlUrl: string | null;
  description: string | null;
  isPrivate: boolean;
  status: 'CONNECTED' | 'ARCHIVED' | 'ERROR' | 'DISCONNECTED';
  connectedBy: string;
  connectedAt: string;
  lastSyncedAt: string | null;
}

export interface PullRequest {
  id: string;
  repositoryId: string;
  number: number;
  title: string;
  state: 'OPEN' | 'CLOSED' | 'MERGED';
  authorLogin: string | null;
  headBranch: string;
  baseBranch: string;
  headSha: string;
  htmlUrl: string | null;
  isDraft: boolean;
  openedAt: string;
  updatedAt: string;
  closedAt: string | null;
  mergedAt: string | null;
  lastIngestedAt: string;
}

export const githubApi = {
  async getAuthorizeUrl(): Promise<GithubAuthorizeResponse> {
    const res = await apiClient.get<GithubAuthorizeResponse>('/github/oauth/authorize-url');
    return res.data;
  },

  async completeCallback(code: string, state: string, expectedState: string): Promise<void> {
    await apiClient.get('/github/oauth/callback', {
      params: { code, state, expectedState },
    });
  },

  async getConnection(): Promise<GithubConnection | null> {
    const res = await apiClient.get<GithubConnection>('/github/connection');
    return res.data;
  },

  async disconnect(): Promise<void> {
    await apiClient.delete('/github/connection');
  },

  async listCatalog(): Promise<GithubRepositoryCatalogItem[]> {
    const res = await apiClient.get<{ repositories: GithubRepositoryCatalogItem[] }>(
      '/github/catalog/repositories',
    );
    return res.data.repositories;
  },

  async getRepositoryByUrl(url: string): Promise<GithubRepositoryCatalogItem> {
    const res = await apiClient.get<GithubRepositoryCatalogItem>(
      '/github/repositories/by-url',
      { params: { url } },
    );
    return res.data;
  },

  async listRepositories(workspaceId: string): Promise<Repository[]> {
    const res = await apiClient.get<Repository[]>(`/workspaces/${workspaceId}/repositories`);
    return res.data;
  },

  async connectRepository(
    workspaceId: string,
    githubId: number,
    defaultBranch?: string,
  ): Promise<Repository> {
    const res = await apiClient.post<Repository>(`/workspaces/${workspaceId}/repositories`, {
      githubId,
      defaultBranch,
    });
    return res.data;
  },

  async disconnectRepository(workspaceId: string, repositoryId: string): Promise<void> {
    await apiClient.delete(`/workspaces/${workspaceId}/repositories/${repositoryId}`);
  },

  async syncRepository(workspaceId: string, repositoryId: string): Promise<Repository> {
    const res = await apiClient.post<Repository>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/sync`,
    );
    return res.data;
  },

  async listPullRequests(workspaceId: string, repositoryId: string): Promise<PullRequest[]> {
    const res = await apiClient.get<PullRequest[]>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/pulls`,
    );
    return res.data;
  },

  async ingestPullRequests(
    workspaceId: string,
    repositoryId: string,
    state: 'open' | 'closed' | 'all' = 'all',
  ): Promise<PullRequest[]> {
    const res = await apiClient.post<PullRequest[]>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/pulls/ingest`,
      { state },
    );
    return res.data;
  },
};
