/**
 * Platform-wide governance defaults API.
 *
 * <p>These are the values that new workspaces inherit on creation.
 * Existing workspaces keep their per-workspace settings.
 */
import apiClient from './api';

export interface PlatformSettings {
  defaultAutoUpdateOnCommit: boolean;
  defaultAutoUpdateOnPr: boolean;
  defaultAutoUpdateOnMerge: boolean;
  defaultDriftSeverityThreshold: string;
  defaultRequireManagerApproval: boolean;
  defaultMergePolicyCritical: string;
  defaultAiConfidenceThreshold: number;
  updatedAt: string;
}

export interface PlatformDocTypePolicy {
  docType: string;
  workflow: 'AUTO_APPLY' | 'MANAGER_REVIEW';
  updatedAt: string;
}

export interface UpdatePlatformSettingsPayload {
  defaultAutoUpdateOnCommit: boolean;
  defaultAutoUpdateOnPr: boolean;
  defaultAutoUpdateOnMerge: boolean;
  defaultDriftSeverityThreshold: string;
  defaultRequireManagerApproval: boolean;
  defaultMergePolicyCritical: string;
  defaultAiConfidenceThreshold: number;
}

export interface UpdatePlatformDocTypePolicyPayload {
  workflow: 'AUTO_APPLY' | 'MANAGER_REVIEW';
}

export const platformSettingsApi = {
  get: async (): Promise<PlatformSettings> => {
    const { data } = await apiClient.get<PlatformSettings>('/admin/platform-settings');
    return data;
  },

  update: async (payload: UpdatePlatformSettingsPayload): Promise<PlatformSettings> => {
    const { data } = await apiClient.put<PlatformSettings>('/admin/platform-settings', payload);
    return data;
  },

  listDocTypePolicies: async (): Promise<PlatformDocTypePolicy[]> => {
    const { data } = await apiClient.get<PlatformDocTypePolicy[]>(
      '/admin/platform-settings/doc-type-policies',
    );
    return data;
  },

  upsertDocTypePolicy: async (docType: string,
                                payload: UpdatePlatformDocTypePolicyPayload): Promise<PlatformDocTypePolicy> => {
    const { data } = await apiClient.put<PlatformDocTypePolicy>(
      `/admin/platform-settings/doc-type-policies/${encodeURIComponent(docType)}`,
      payload,
    );
    return data;
  },

  deleteDocTypePolicy: async (docType: string): Promise<void> => {
    await apiClient.delete(
      `/admin/platform-settings/doc-type-policies/${encodeURIComponent(docType)}`,
    );
  },
};
