/**
 * Governance policy API client.
 *
 * <p>These endpoints expose the policy the runtime *actually* enforces,
 * rather than the values a user typed into the settings form. The
 * difference matters: a per-doc-type policy overrides the workspace
 * default, so the settings form alone is not enough to explain why a
 * version was routed to review instead of being published.
 */
import apiClient from './api';

export type MergePolicy = 'WARN' | 'BLOCK';
export type DocTypeWorkflow = 'AUTO_APPLY' | 'MANAGER_REVIEW';

export interface GovernancePolicy {
  workspaceId: string;
  requireManagerApproval: boolean;
  confidenceThreshold: number;
  mergePolicy: MergePolicy;
  blocksOnCriticalDrift: boolean;
  driftSeverityThreshold: string;
}

export interface DocTypePolicy {
  workspaceId: string;
  docType: string;
  workflow: DocTypeWorkflow;
  autoApply: boolean;
}

export interface MergeGateVerdict {
  workspaceId: string;
  pullRequestId: string | null;
  blocked: boolean;
  policy: MergePolicy;
  severityThreshold: string;
  blockingAlertCount: number;
  blockingAlertIds: string[];
  reason: string;
}

export const governanceApi = {
  /** The effective policy for a workspace. */
  policy(workspaceId: string) {
    return apiClient
      .get<GovernancePolicy>(`/workspaces/${workspaceId}/governance`)
      .then((r) => r.data);
  },

  /** The workflow that applies to one documentation type. */
  docTypePolicy(workspaceId: string, docType: string) {
    return apiClient
      .get<DocTypePolicy>(
        `/workspaces/${workspaceId}/governance/doc-types/${encodeURIComponent(docType)}`,
      )
      .then((r) => r.data);
  },

  /** Evaluate the merge gate for a pull request. */
  mergeGate(workspaceId: string, pullRequestId?: string) {
    const qs = pullRequestId ? `?pullRequestId=${pullRequestId}` : '';
    return apiClient
      .get<MergeGateVerdict>(
        `/workspaces/${workspaceId}/governance/merge-gate${qs}`,
      )
      .then((r) => r.data);
  },
};
