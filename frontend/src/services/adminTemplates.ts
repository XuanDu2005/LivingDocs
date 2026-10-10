/**
 * Admin global template library API client.
 *
 * <p>Mirrors the workspace template client but talks to the admin-only
 * endpoints at {@code /api/v1/admin/templates}.
 */
import apiClient from './api';

export interface AdminDocTemplate {
  id: string;
  workspaceId: string | null;
  name: string;
  slug: string;
  description: string | null;
  docType: string;
  version: number;
  bodyJson: string;
  outputFormat?: 'MARKDOWN' | 'HTML' | 'PDF' | null;
  autoGenerateOnCommit?: boolean;
  autoGenerateOnPr?: boolean;
  autoGenerateOnMerge?: boolean;
  isDefault: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdminDocTemplateListResponse {
  workspaceTemplates: AdminDocTemplate[];
  globalTemplates: AdminDocTemplate[];
}

export interface AdminCreateTemplatePayload {
  name: string;
  slug: string;
  description?: string;
  docType: string;
  bodyJson: string;
  outputFormat?: 'MARKDOWN' | 'HTML' | 'PDF' | null;
  autoGenerateOnCommit?: boolean;
  autoGenerateOnPr?: boolean;
  autoGenerateOnMerge?: boolean;
  isDefault: boolean;
}

export interface AdminUpdateTemplatePayload {
  name: string;
  description?: string;
  bodyJson: string;
  outputFormat?: 'MARKDOWN' | 'HTML' | 'PDF' | null;
  autoGenerateOnCommit?: boolean;
  autoGenerateOnPr?: boolean;
  autoGenerateOnMerge?: boolean;
  isDefault: boolean;
}

export const adminTemplatesApi = {
  list: async (): Promise<AdminDocTemplateListResponse> => {
    const { data } = await apiClient.get<AdminDocTemplateListResponse>('/admin/templates');
    return data;
  },

  get: async (templateId: string): Promise<AdminDocTemplate> => {
    const { data } = await apiClient.get<AdminDocTemplate>(`/admin/templates/${templateId}`);
    return data;
  },

  create: async (payload: AdminCreateTemplatePayload): Promise<AdminDocTemplate> => {
    const { data } = await apiClient.post<AdminDocTemplate>('/admin/templates', payload);
    return data;
  },

  update: async (templateId: string,
                 payload: AdminUpdateTemplatePayload): Promise<AdminDocTemplate> => {
    const { data } = await apiClient.put<AdminDocTemplate>(
      `/admin/templates/${templateId}`,
      payload,
    );
    return data;
  },

  remove: async (templateId: string): Promise<void> => {
    await apiClient.delete(`/admin/templates/${templateId}`);
  },

  rollback: async (templateId: string, version: number): Promise<AdminDocTemplate> => {
    const { data } = await apiClient.post<AdminDocTemplate>(
      `/admin/templates/${templateId}/rollback`,
      null,
      { params: { version } },
    );
    return data;
  },

  listVersions: async (templateId: string): Promise<AdminDocTemplate[]> => {
    // The admin controller doesn't expose /versions; we filter the global
    // list by slug on the client. The workspace controller has the
    // version endpoint and could be wired up later.
    const list = await adminTemplatesApi.list();
    const tpl = list.globalTemplates.find((t) => t.id === templateId);
    if (!tpl) return [];
    return list.globalTemplates
      .filter((t) => t.slug === tpl.slug)
      .slice()
      .sort((a, b) => b.version - a.version);
  },

  setDefault: async (templateId: string): Promise<AdminDocTemplate> => {
    const { data } = await apiClient.post<AdminDocTemplate>(
      `/admin/templates/${templateId}/set-default`,
    );
    return data;
  },
};
