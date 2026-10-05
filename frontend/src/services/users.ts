import apiClient from './api';
import { AuthenticatedUser } from './auth';

export interface UpdateProfilePayload {
  displayName: string;
  email?: string;
}

export async function fetchCurrentUser(): Promise<AuthenticatedUser> {
  const { data } = await apiClient.get<AuthenticatedUser>('/users/me');
  return data;
}

export async function updateCurrentUser(
  payload: UpdateProfilePayload,
): Promise<AuthenticatedUser> {
  const { data } = await apiClient.put<AuthenticatedUser>('/users/me', payload);
  return data;
}