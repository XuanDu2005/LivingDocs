import apiClient from './api';
import { AuthSuccess } from './auth';

export interface LoginPayload {
  email: string;
  password: string;
}

export interface RegisterPayload {
  email: string;
  password: string;
  displayName: string;
}

export interface VerifyEmailPayload {
  email: string;
  code: string;
}

export interface RegistrationPending {
  requiresEmailVerification: boolean;
  email: string;
  verificationTtlMinutes: number;
}

export async function login(payload: LoginPayload): Promise<AuthSuccess> {
  const { data } = await apiClient.post<AuthSuccess>('/auth/login', payload);
  return data;
}

export async function register(payload: RegisterPayload): Promise<RegistrationPending> {
  const { data } = await apiClient.post<RegistrationPending>('/auth/register', payload);
  return data;
}

export async function verifyEmail(payload: VerifyEmailPayload): Promise<AuthSuccess> {
  const { data } = await apiClient.post<AuthSuccess>('/auth/verify-email', payload);
  return data;
}

export async function resendVerification(email: string): Promise<void> {
  await apiClient.post<void>('/auth/resend-verification', { email });
}

export async function forgotPassword(email: string): Promise<void> {
  await apiClient.post<void>('/auth/forgot-password', { email });
}

export async function resetPassword(
  email: string,
  code: string,
  newPassword: string,
): Promise<void> {
  await apiClient.post<void>('/auth/reset-password', { email, code, newPassword });
}

export interface LinkedProvider {
  provider: 'google' | 'github';
  providerEmail: string | null;
  displayName: string | null;
  avatarUrl: string | null;
}

export async function listLinkedProviders(): Promise<LinkedProvider[]> {
  const { data } = await apiClient.get<{ providers: LinkedProvider[] }>('/auth/oauth/providers');
  return data.providers;
}

export async function unlinkProvider(provider: 'google' | 'github'): Promise<void> {
  await apiClient.delete<void>(`/auth/oauth/${provider}/link`);
}

export async function confirmMergeOAuth(
  provider: 'google' | 'github',
  params: { providerUserId: string; providerEmail: string; displayName?: string },
): Promise<LinkedProvider> {
  const { data } = await apiClient.post<LinkedProvider>(
    `/auth/oauth/${provider}/link/confirm-merge`,
    null,
    { params },
  );
  return data;
}
