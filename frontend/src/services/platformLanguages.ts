/**
 * Platform-level programming language administration.
 *
 * <p>Each row in this list is the default that every newly-created
 * workspace inherits. The workspace-level page can still toggle, edit
 * a custom prompt, or disable a language independently.
 */
import apiClient from './api';

export interface PlatformLanguage {
  id: string;
  languageCode: string;
  languageName: string;
  enabled: boolean;
  defaultPrompt: string | null;
  sortOrder: number;
  updatedAt: string;
}

export interface UpdatePlatformLanguagePayload {
  languageName: string;
  enabled: boolean;
  defaultPrompt: string | null;
  sortOrder: number;
}

export const platformLanguagesApi = {
  list: async (): Promise<PlatformLanguage[]> => {
    const { data } = await apiClient.get<PlatformLanguage[]>('/admin/languages');
    return data;
  },

  update: async (languageCode: string,
                 payload: UpdatePlatformLanguagePayload): Promise<PlatformLanguage> => {
    const { data } = await apiClient.put<PlatformLanguage>(
      `/admin/languages/${encodeURIComponent(languageCode)}`,
      payload,
    );
    return data;
  },
};
