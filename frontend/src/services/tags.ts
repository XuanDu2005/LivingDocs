import { apiClient } from './api';

export interface DocumentTag {
  id: string;
  name: string;
  colorHex: string;
}

export const tagsApi = {
  getWorkspaceTags: async (workspaceId: string): Promise<DocumentTag[]> => {
    const { data } = await apiClient.get(`/workspaces/${workspaceId}/tags`);
    return data;
  },
  createTag: async (workspaceId: string, name: string, colorHex: string): Promise<DocumentTag> => {
    const { data } = await apiClient.post(`/workspaces/${workspaceId}/tags`, { name, colorHex });
    return data;
  },
  getDocumentTags: async (workspaceId: string, documentId: string): Promise<DocumentTag[]> => {
    const { data } = await apiClient.get(`/workspaces/${workspaceId}/documents/${documentId}/tags`);
    return data;
  },
  assignTag: async (workspaceId: string, documentId: string, tagId: string): Promise<void> => {
    await apiClient.post(`/workspaces/${workspaceId}/documents/${documentId}/tags/${tagId}`);
  },
  removeTag: async (workspaceId: string, documentId: string, tagId: string): Promise<void> => {
    await apiClient.delete(`/workspaces/${workspaceId}/documents/${documentId}/tags/${tagId}`);
  }
};