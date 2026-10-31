/**
 * Doc Update Request API client.
 *
 * DocUpdateRequest is the "review-before-push" workflow: when the
 * system detects a code change that should propagate to a doc, a
 * proposal is queued in PENDING_REVIEW. Reviewers (Staff) approve or
 * reject, then a Manager calls `apply` to push the content as a real
 * PR on GitHub.
 *
 * Endpoints (all scoped to workspace + repository):
 *   GET  /api/v1/workspaces/{wsId}/repositories/{repoId}/doc-updates
 *   GET  /api/v1/workspaces/{wsId}/repositories/{repoId}/doc-updates/{id}
 *   POST /api/v1/workspaces/{wsId}/repositories/{repoId}/doc-updates/{id}/approve
 *   POST /api/v1/workspaces/{wsId}/repositories/{repoId}/doc-updates/{id}/reject
 *   POST /api/v1/workspaces/{wsId}/repositories/{repoId}/doc-updates/{id}/apply
 */
import apiClient, { LONG_TIMEOUT_MS } from './api';

export type DocUpdateStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'APPLIED';

export interface DocUpdateRequest {
  id: string;
  workspaceId: string;
  repositoryId: string;
  documentId: string | null;
  filePath: string;
  triggerCommitSha: string | null;
  triggerBranch: string | null;
  currentContent: string | null;
  proposedContent: string;
  diffSummary: string | null;
  status: DocUpdateStatus;
  requestedByUserId: string | null;
  requestedAt: string;
  reviewedByUserId: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  appliedAt: string | null;
  appliedPrUrl: string | null;
}

export interface ReviewDecisionBody {
  comment?: string;
}

export const docUpdateRequestApi = {
  /**
   * List doc update requests for a repository, optionally filtered by status.
   */
  list(workspaceId: string, repositoryId: string, status?: DocUpdateStatus) {
    const qs = status ? `?status=${encodeURIComponent(status)}` : '';
    return apiClient
      .get<DocUpdateRequest[]>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-updates${qs}`,
      )
      .then((r) => r.data);
  },

  /**
   * Fetch a single proposal with full current/proposed content for the
   * diff view.
   */
  get(workspaceId: string, repositoryId: string, requestId: string) {
    return apiClient
      .get<DocUpdateRequest>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-updates/${requestId}`,
      )
      .then((r) => r.data);
  },

  /**
   * Approve a pending proposal. Any workspace member can do this.
   * After approval the proposal is ready for a Manager to call `apply`.
   */
  approve(workspaceId: string, repositoryId: string, requestId: string, body?: ReviewDecisionBody) {
    return apiClient
      .post<DocUpdateRequest>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-updates/${requestId}/approve`,
        body ?? {},
      )
      .then((r) => r.data);
  },

  /**
   * Reject a pending proposal. The doc is left untouched.
   */
  reject(workspaceId: string, repositoryId: string, requestId: string, body?: ReviewDecisionBody) {
    return apiClient
      .post<DocUpdateRequest>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-updates/${requestId}/reject`,
        body ?? {},
      )
      .then((r) => r.data);
  },

  /**
   * Manager-only: push the approved content to GitHub as a PR.
   * Backend returns the updated request with `appliedPrUrl` set.
   *
   * Creating the branch and the pull request is several GitHub API
   * round-trips, so this needs the long timeout.
   */
  apply(workspaceId: string, repositoryId: string, requestId: string) {
    return apiClient
      .post<DocUpdateRequest>(
        `/workspaces/${workspaceId}/repositories/${repositoryId}/doc-updates/${requestId}/apply`,
        {},
        { timeout: LONG_TIMEOUT_MS },
      )
      .then((r) => r.data);
  },
};
