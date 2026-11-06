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
  autoUpdateEnabled: boolean;
}

/**
 * A module (directory / package) of a repository whose documents can be
 * excluded from AI auto-update.
 */
export interface RepositoryModule {
  id: string | null;
  repositoryId: string;
  workspaceId: string;
  /** Normalised path prefix, e.g. "/src/auth". */
  modulePath: string;
  displayName: string | null;
  autoUpdateEnabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
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
    await apiClient.post('/github/oauth/complete', null, {
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
    // Backend exposes the state filter as a query parameter (see
    // {@code GithubRepositoryController.ingestPulls}), so it must be
    // sent on the URL — putting it in the JSON body would be ignored
    // and the controller would fall back to its default ("all"), which
    // is incidentally what we want here, but only by luck. Be explicit
    // about it so a future "state" body field doesn't conflict.
    const res = await apiClient.post<PullRequest[]>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/pulls/ingest?state=${state}`,
    );
    return res.data;
  },

  async ingestCommits(workspaceId: string, repositoryId: string, perPage = 30) {
    const res = await apiClient.post<{ ingested: number; commits: Commit[] }>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/commits/ingest?perPage=${perPage}`,
    );
    return res.data;
  },

  async ingestCode(workspaceId: string, repositoryId: string, branch?: string) {
    const res = await apiClient.post<{ ingested: number }>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/code-entities/ingest`,
      null,
      { params: branch ? { branch } : {} },
    );
    return res.data;
  },

  async listCommits(workspaceId: string, repositoryId: string, limit = 50): Promise<Commit[]> {
    const res = await apiClient.get<Commit[]>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/commits?limit=${limit}`,
    );
    return res.data;
  },

  async setRepositoryAutoUpdate(
    workspaceId: string,
    repositoryId: string,
    enabled: boolean,
  ): Promise<Repository> {
    const res = await apiClient.put<Repository>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/auto-update?enabled=${enabled}`,
    );
    return res.data;
  },

  // -----------------------------------------------------------------
  // Per-module auto-update
  //
  // The repository toggle and the document toggle bracket this one: a
  // workspace can exclude a single package from auto-updating while
  // the rest of the repository keeps updating.
  // -----------------------------------------------------------------

  async listModules(workspaceId: string, repositoryId: string): Promise<RepositoryModule[]> {
    const res = await apiClient.get<RepositoryModule[]>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/modules`,
    );
    return res.data;
  },

  /** The module directories detected from the indexed code entities. */
  async detectModules(workspaceId: string, repositoryId: string): Promise<RepositoryModule[]> {
    const res = await apiClient.get<RepositoryModule[]>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/modules/detected`,
    );
    return res.data;
  },

  async upsertModule(
    workspaceId: string,
    repositoryId: string,
    modulePath: string,
    autoUpdateEnabled: boolean,
    displayName?: string,
  ): Promise<RepositoryModule> {
    const res = await apiClient.post<RepositoryModule>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/modules`,
      { modulePath, displayName, autoUpdateEnabled },
    );
    return res.data;
  },

  async setModuleAutoUpdate(
    workspaceId: string,
    moduleId: string,
    enabled: boolean,
  ): Promise<RepositoryModule> {
    const res = await apiClient.put<RepositoryModule>(
      `/workspaces/${workspaceId}/modules/${moduleId}/auto-update?enabled=${enabled}`,
    );
    return res.data;
  },

  async deleteModule(workspaceId: string, moduleId: string): Promise<void> {
    await apiClient.delete(`/workspaces/${workspaceId}/modules/${moduleId}`);
  },

  /**
   * Publish an approved document version to a repository as a new-branch
   * pull request.
   *
   * The backend creates a feature branch, writes the file via the
   * Contents API, and opens a PR against the repository's default
   * branch.
   *
   * The commit is gated on the review workflow: `documentId` and
   * `versionNumber` must name a version that carries a Manager
   * approval, and `content` must contain that version's body. The
   * backend answers 409 when the version is not approved or when the
   * content does not match it.
   */
  async commitDocument(
    workspaceId: string,
    repositoryId: string,
    req: {
      filePath: string;
      content: string;
      commitMessage: string;
      prTitle: string;
      prBody?: string;
      headBranch?: string;
      documentId: string;
      versionNumber: number;
    },
  ): Promise<{ pullRequestUrl: string; headBranch: string; filePath: string; commitMessage: string }> {
    const res = await apiClient.post<{ pullRequestUrl: string; headBranch: string; filePath: string; commitMessage: string }>(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/commit`,
      req,
    );
    return res.data;
  },
};

export interface Commit {
  id: string;
  repositoryId: string;
  sha: string;
  shortSha: string;
  message: string | null;
  authorName: string | null;
  authorEmail: string | null;
  authoredAt: string;
  committedAt: string | null;
  url: string | null;
  branch: string | null;
}
