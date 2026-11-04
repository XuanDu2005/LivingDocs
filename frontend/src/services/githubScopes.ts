/**
 * GitHub connection scope review.
 *
 * <p>The OAuth handshake asks GitHub for a fixed scope set. This client
 * exposes the reviewable view of it: which scopes are in force, what
 * each one allows, and how to re-consent with a narrower set.
 */
import apiClient from './api';

export interface GithubScope {
  id: string;
  scope: string;
  description: string | null;
  active: boolean;
  grantedAt: string | null;
  revokedAt: string | null;
}

/** One scope the platform knows about, and whether it is currently granted. */
export interface ScopeDefinition {
  scope: string;
  description: string;
  usage: string;
  granted: boolean;
}

export interface ScopeOverview {
  githubLogin: string | null;
  connectionStatus: string | null;
  available: ScopeDefinition[];
  grants: GithubScope[];
}

export const githubScopesApi = {
  /** The scopes in force plus the ones the platform could ask for. */
  overview() {
    return apiClient
      .get<ScopeOverview>('/github/scopes')
      .then((r) => r.data);
  },

  /**
   * Withdraw a granted scope.
   *
   * <p>A scope the platform needs cannot be dropped without
   * re-authorizing, so the backend refuses unless `force` is set.
   */
  revoke(scope: string, force = false) {
    return apiClient
      .delete<GithubScope>(`/github/scopes/${encodeURIComponent(scope)}?force=${force}`)
      .then((r) => r.data);
  },

  /**
   * Get a GitHub authorize URL for a narrower scope set, so the user can
   * actually re-consent with fewer permissions.
   */
  reauthorize(scopes: string[]) {
    return apiClient
      .post<{ url: string; state: string }>('/github/scopes/reauthorize', scopes)
      .then((r) => r.data);
  },
};
