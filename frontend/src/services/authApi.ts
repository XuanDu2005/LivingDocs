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

export async function login(payload: LoginPayload): Promise<AuthSuccess> {
  const { data } = await apiClient.post<AuthSuccess>('/auth/login', payload);
  return data;
}

export async function register(payload: RegisterPayload): Promise<AuthSuccess> {
  const { data } = await apiClient.post<AuthSuccess>('/auth/register', payload);
  return data;
}