/**
 * Frontend client for the canonical template schema metadata endpoint.
 */
import apiClient from './api';
import {
  TemplateSchemaResponse,
} from '../types/templateBody';

export const templateSchemaApi = {
  get: async (): Promise<TemplateSchemaResponse> => {
    const { data } = await apiClient.get<TemplateSchemaResponse>('/template-schema');
    return data;
  },
};
