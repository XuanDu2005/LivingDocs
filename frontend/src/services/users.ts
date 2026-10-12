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

export type DigestFrequency = 'REAL_TIME' | 'DAILY' | 'WEEKLY';

export interface UserNotificationPreferences {
  emailOnDriftDetected: boolean;
  emailOnReviewRequested: boolean;
  emailOnAutoUpdate: boolean;
  emailOnTokenInvalid: boolean;
  inAppOnDriftDetected: boolean;
  inAppOnReviewRequested: boolean;
  inAppOnAutoUpdate: boolean;
  inAppOnTokenInvalid: boolean;
  digestFrequency: DigestFrequency;
}

export async function fetchNotificationPreferences(): Promise<UserNotificationPreferences> {
  const { data } = await apiClient.get<UserNotificationPreferences>('/users/me/notification-preferences');
  return data;
}

export async function updateNotificationPreferences(
  prefs: UserNotificationPreferences,
): Promise<UserNotificationPreferences> {
  const { data } = await apiClient.put<UserNotificationPreferences>('/users/me/notification-preferences', prefs);
  return data;
}