import axios from 'axios';

/*Create a separate, small instance using the same baseUrl.*/
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1',
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
});

/* Add an interceptor to automatically get the token from localStorage and assign it to the header */
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('livingdocs_auth_token');
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

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