import apiClient from './api';

export interface WorkspaceLanguage {
  id: string;
  workspaceId: string;
  languageCode: string;
  languageName: string;
  enabled: boolean;
  customPrompt: string | null;
  updatedAt: string;
}

export interface UpdateLanguagePayload {
  languageCode: string;
  languageName: string;
  enabled: boolean;
  customPrompt: string | null;
}

export const workspaceLanguagesApi = {
  list: async (workspaceId: string): Promise<WorkspaceLanguage[]> => {
    const { data } = await apiClient.get<WorkspaceLanguage[]>(
      `/workspaces/${workspaceId}/languages`
    );
    return data;
  },

  update: async (workspaceId: string, payload: UpdateLanguagePayload): Promise<WorkspaceLanguage> => {
    const { data } = await apiClient.put<WorkspaceLanguage>(
      `/workspaces/${workspaceId}/languages`,
      payload
    );
    return data;
  },
};

// Predefined list of supported languages
export const SUPPORTED_LANGUAGES: { code: string; name: string }[] = [
  { code: 'javascript', name: 'JavaScript' },
  { code: 'typescript', name: 'TypeScript' },
  { code: 'python', name: 'Python' },
  { code: 'java', name: 'Java' },
  { code: 'go', name: 'Go' },
  { code: 'rust', name: 'Rust' },
  { code: 'cpp', name: 'C++' },
  { code: 'csharp', name: 'C#' },
  { code: 'ruby', name: 'Ruby' },
  { code: 'php', name: 'PHP' },
  { code: 'swift', name: 'Swift' },
  { code: 'kotlin', name: 'Kotlin' },
  { code: 'scala', name: 'Scala' },
  { code: 'sql', name: 'SQL' },
  { code: 'shell', name: 'Shell/Bash' },
];
