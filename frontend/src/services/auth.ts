import { AxiosError, AxiosInstance } from 'axios';

import apiClient from './api';

/**
 * Token storage for the JWT bearer token issued by /api/v1/auth.
 *
 * <p>Stored in {@link localStorage} so it survives page reloads. In a more
 * security-sensitive deployment you'd move it to an HttpOnly cookie set by the
 * backend; that's out of scope for the foundation.
 */
const TOKEN_KEY = 'livingdocs.auth.token';
const USER_KEY = 'livingdocs.auth.user';

export interface AuthenticatedUser {
  id: string;
  email: string;
  displayName: string;
  enabled: boolean;
  emailVerified?: boolean;
  createdAt: string;
  /**
   * Active platform role codes (e.g. ADMIN, MANAGER, STAFF,
   * TECHNICAL_LEAD, DEVELOPER). Empty array means the user has no
   * platform role assignments yet.
   *
   * <p>Replaces the legacy single-value {@code role} field. The
   * server-side token ({@code JWT_CLAIM_ROLES}) and {@code /users/me}
   * response both carry the same set of codes so the sidebar and admin
   * checks can be driven by {@code roles.includes('ADMIN')}.
   */
  roles?: string[];
  /** @deprecated Use {@link roles} (array) instead. Kept for backward compat. */
  role?: 'MEMBER' | 'MANAGER' | 'ADMIN';
}

export interface AuthSuccess {
  token: string;
  expiresAt: string;
  user: AuthenticatedUser;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  violations?: Array<{ field: string; message: string }>;
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = 'ApiError';
  }
}

/**
 * Convert any thrown value into an {@link ApiError}. Use this whenever a
 * component needs both the message and the HTTP status (for example to
 * decide whether to show a retry button or a 'reconnect' flow).
 */
export function asApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err;
  if (err instanceof AxiosError) {
    const data = err.response?.data as ApiErrorBody | undefined;
    return new ApiError(err.response?.status ?? 0, data?.message ?? err.message);
  }
  if (err instanceof Error) return new ApiError(0, err.message);
  return new ApiError(0, 'Unexpected error');
}

/**
 * Normalize any thrown axios error into a single string message that the UI
 * can render directly.
 */
export function describeError(err: unknown): string {
  if (err instanceof AxiosError) {
    const data = err.response?.data as ApiErrorBody | undefined;
    if (data?.message) {
      return data.message;
    }
    return err.message;
  }
  if (err instanceof Error) {
    return err.message;
  }
  return 'Unexpected error';
}

/**
 * Returns true if the user holds the given platform role code. Supports
 * the legacy single-value {@code role} field for users whose stored
 * profile hasn't been refreshed yet, and an empty/missing role list is
 * treated as "no privileges" (returns false).
 */
export function hasRole(user: AuthenticatedUser | null | undefined, code: string): boolean {
  if (!user) return false;
  if (Array.isArray(user.roles) && user.roles.includes(code)) return true;
  if (user.role === code) return true;
  return false;
}

export const authStorage = {
  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  },
  setToken(token: string): void {
    localStorage.setItem(TOKEN_KEY, token);
  },
  clearToken(): void {
    localStorage.removeItem(TOKEN_KEY);
  },
  getUser(): AuthenticatedUser | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as AuthenticatedUser;
    } catch {
      return null;
    }
  },
  setUser(user: AuthenticatedUser): void {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  },
  clearUser(): void {
    localStorage.removeItem(USER_KEY);
  },
};

/**
 * Install an interceptor on the shared apiClient that injects the bearer
 * token when one is available.
 */
export function installAuthInterceptor(client: AxiosInstance = apiClient): void {
  client.interceptors.request.use((config) => {
    const token = authStorage.getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });

  client.interceptors.response.use(
    (response) => response,
    (err: AxiosError) => {
      if (err.response?.status === 401) {
        // Token expired or revoked — clear local credentials. The AuthContext
        // listens to storage changes via its own subscriber so it can react.
        authStorage.clearToken();
        authStorage.clearUser();
      }
      return Promise.reject(err);
    },
  );
}