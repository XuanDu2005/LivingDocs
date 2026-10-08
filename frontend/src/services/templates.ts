/**
 * Documentation templates API client.
 */
import apiClient from './api';

export interface DocTemplate {
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

export interface DocTemplateListResponse {
  workspaceTemplates: DocTemplate[];
  globalTemplates: DocTemplate[];
}

export const templatesApi = {
  list(workspaceId: string) {
    return apiClient
      .get<DocTemplateListResponse>(`/workspaces/${workspaceId}/templates`)
      .then((r) => r.data);
  },
  get(workspaceId: string, templateId: string) {
    return apiClient
      .get<DocTemplate>(`/workspaces/${workspaceId}/templates/${templateId}`)
      .then((r) => r.data);
  },
  create(
    workspaceId: string,
    payload: {
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
    },
  ) {
    return apiClient
      .post<DocTemplate>(`/workspaces/${workspaceId}/templates`, payload)
      .then((r) => r.data);
  },
  update(
    workspaceId: string,
    templateId: string,
    payload: {
      name: string;
      description?: string;
      bodyJson: string;
      outputFormat?: 'MARKDOWN' | 'HTML' | 'PDF' | null;
      autoGenerateOnCommit?: boolean;
      autoGenerateOnPr?: boolean;
      autoGenerateOnMerge?: boolean;
      isDefault: boolean;
    },
  ) {
    return apiClient
      .put<DocTemplate>(
        `/workspaces/${workspaceId}/templates/${templateId}`,
        payload,
      )
      .then((r) => r.data);
  },
  remove(workspaceId: string, templateId: string) {
    return apiClient.delete<void>(
      `/workspaces/${workspaceId}/templates/${templateId}`,
    );
  },
};