/**
 * CI/CD API client.
 *
 * <p>These endpoints are the CI-facing half of the merge gate. The
 * generated GitHub Actions workflow calls the merge-gate endpoint with
 * the workspace CI token; the workspace manager uses the token endpoints
 * to issue that credential.
 *
 * <p>The CI token is <em>not</em> a user JWT — the platform stores only
 * its hash, so the plaintext is shown once and cannot be retrieved
 * later.
 */
import apiClient from './api';

export interface CiTokenStatus {
  workspaceId: string;
  hasToken: boolean;
}

export interface CiTokenIssued {
  workspaceId: string;
  /** The plaintext token. Shown exactly once. */
  token: string;
  issuedAt: string;
}

export const cicdApi = {
  /** Whether this workspace already has a CI token. */
  tokenStatus: async (workspaceId: string): Promise<CiTokenStatus> => {
    const { data } = await apiClient.get<CiTokenStatus>(
      `/workspaces/${workspaceId}/ci-token`,
    );
    return data;
  },

  /** Issue a CI token, or rotate the existing one. */
  rotateToken: async (workspaceId: string): Promise<CiTokenIssued> => {
    const { data } = await apiClient.post<CiTokenIssued>(
      `/workspaces/${workspaceId}/ci-token/rotate`,
    );
    return data;
  },

  /** Revoke the CI token. */
  revokeToken: async (workspaceId: string): Promise<void> => {
    await apiClient.delete(`/workspaces/${workspaceId}/ci-token`);
  },

  /**
   * Download the generated GitHub Actions workflow.
   *
   * <p>Returned as text rather than through `apiClient.get`, because
   * the endpoint answers with `application/x-yaml` and the Axios
   * JSON transform would mangle it.
   */
  githubActionsWorkflow: async (
    workspaceId: string,
    repositoryId: string,
  ): Promise<string> => {
    const res = await apiClient.get(
      `/workspaces/${workspaceId}/repositories/${repositoryId}/github-actions.yml`,
      { responseType: 'text', transformResponse: [(d) => d] },
    );
    return typeof res.data === 'string' ? res.data : String(res.data);
  },
};
