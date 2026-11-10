/**
 * Per-workspace doc-type policy API.
 *
 * <p>The platform holds a default workflow per documentation type
 * (`/admin/platform-settings/doc-type-policies`). These endpoints let a
 * workspace manager override that default for their own workspace —
 * e.g. require a manager approval for `API_REFERENCE` while `README`
 * stays auto-applied.
 *
 * <p>Overrides are sparse: a row only exists for the doc types the
 * workspace actually changed. The response reports the *effective*
 * workflow together with its `scope`, so the UI can tell "I set this"
 * apart from "this is inherited".
 */
import apiClient from './api';
import { reviewsApi, WorkspaceCapabilities } from './drift';

export type DocTypeWorkflow = 'AUTO_APPLY' | 'MANAGER_REVIEW';

/** Where the effective workflow comes from. */
export type DocTypePolicyScope = 'WORKSPACE' | 'PLATFORM' | 'DEFAULT';

export interface WorkspaceDocTypePolicy {
  docType: string;
  /** WORKSPACE = set here, PLATFORM = inherited from the platform, DEFAULT = no row anywhere. */
  scope: DocTypePolicyScope;
  /** The workflow the runtime actually enforces. */
  workflow: DocTypeWorkflow;
  /** The workspace's own row, or null when it has no override. */
  workspaceWorkflow: DocTypeWorkflow | null;
  /** The platform's row, or null when the platform has no policy for this type. */
  platformWorkflow: DocTypeWorkflow | null;
  updatedAt: string | null;
}

export interface UpdateWorkspaceDocTypePolicyPayload {
  workflow: DocTypeWorkflow;
}

/**
 * Whether the caller may change the rows of this workspace.
 *
 * <p>The API requires <em>both</em> axes: the `GOVERNANCE_MANAGE`
 * capability (the permission interceptor) and a workspace role that owns
 * the documentation governance — the workspace `MANAGER` or the
 * workspace `DOCUMENTATION_OWNER` (the service layer). A TECHNICAL_LEAD
 * holds the capability but is only a workspace MEMBER, so it can read
 * the policies but not change them.
 */
export async function canManageDocTypePolicies(
  workspaceId: string,
  hasGovernanceManage: boolean,
): Promise<boolean> {
  if (!hasGovernanceManage) return false;
  try {
    const capabilities: WorkspaceCapabilities =
      await reviewsApi.capabilities(workspaceId);
    return !!capabilities?.workspaceManager || !!capabilities?.documentationOwner;
  } catch {
    return false;
  }
}

export const workspaceDocTypePoliciesApi = {
  /** The effective policy of every canonical doc type in this workspace. */
  list: async (workspaceId: string): Promise<WorkspaceDocTypePolicy[]> => {
    const { data } = await apiClient.get<WorkspaceDocTypePolicy[]>(
      `/workspaces/${workspaceId}/doc-type-policies`,
    );
    return data;
  },

  /** Set the workflow this workspace applies to one doc type. */
  upsert: async (
    workspaceId: string,
    docType: string,
    payload: UpdateWorkspaceDocTypePolicyPayload,
  ): Promise<WorkspaceDocTypePolicy> => {
    const { data } = await apiClient.put<WorkspaceDocTypePolicy>(
      `/workspaces/${workspaceId}/doc-type-policies/${encodeURIComponent(docType)}`,
      payload,
    );
    return data;
  },

  /** Drop the override so the doc type inherits the platform policy again. */
  remove: async (workspaceId: string, docType: string): Promise<void> => {
    await apiClient.delete(
      `/workspaces/${workspaceId}/doc-type-policies/${encodeURIComponent(docType)}`,
    );
  },
};

/**
 * The canonical documentation types, mirroring the backend
 * `DocType` enum. Used to label the rows of the policy table.
 */
export const DOC_TYPE_LABELS: Record<string, string> = {
  MODULE_GUIDE: 'Module guide',
  API_REFERENCE: 'API reference',
  README: 'README',
  ONBOARDING_GUIDE: 'Onboarding guide',
  ARCHITECTURE: 'Architecture',
  ADR: 'ADR',
  CHANGELOG: 'Changelog',
  RUNBOOK: 'Runbook',
  DATA_DICTION: 'Data dictionary',
  TUTORIAL: 'Tutorial',
  CUSTOM: 'Custom',
};
