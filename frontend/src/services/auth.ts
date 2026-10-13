import { AxiosError, AxiosInstance } from 'axios';

import apiClient from './api';

/**
 * Token storage for the JWT bearer token issued by /api/v1/auth.
 *
 * <p>Stored in {@link localStorage} so it survives page reloads. In a more
 * security-sensitive deployment you'd move it to an HttpOnly cookie set by the
 * backend; that's out of scope for the foundation.
 *
 * <p>The refresh token follows the same storage policy. Both are single-use
 * — the backend rotates refresh tokens on every /auth/refresh call.
 */
const TOKEN_KEY = 'livingdocs.auth.token';
const REFRESH_TOKEN_KEY = 'livingdocs.auth.refreshToken';
const REFRESH_EXPIRES_KEY = 'livingdocs.auth.refreshExpiresAt';
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
   */
  roles?: string[];
  /** @deprecated Use {@link roles} (array) instead. Kept for backward compat. */
  role?: 'MEMBER' | 'MANAGER' | 'ADMIN';
  /**
   * OAuth identities currently linked to this user (Google, GitHub).
   * Populated by the backend in every {@code /users/me} response, so
   * the front-end can render linked-account status anywhere without
   * a separate round-trip and stays in sync across devices.
   */
  linkedProviders?: Array<{
    provider: 'google' | 'github';
    providerEmail: string | null;
    displayName: string | null;
    avatarUrl: string | null;
  }>;
}

export interface AuthSuccess {
  token: string;
  expiresAt: string;
  refreshToken: string;
  refreshExpiresAt: string;
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
  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  },
  setRefreshToken(token: string, expiresAt?: string): void {
    localStorage.setItem(REFRESH_TOKEN_KEY, token);
    if (expiresAt) {
      localStorage.setItem(REFRESH_EXPIRES_KEY, expiresAt);
    } else {
      localStorage.removeItem(REFRESH_EXPIRES_KEY);
    }
  },
  clearRefreshToken(): void {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(REFRESH_EXPIRES_KEY);
  },
  getRefreshExpiresAt(): string | null {
    return localStorage.getItem(REFRESH_EXPIRES_KEY);
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
  clearAll(): void {
    this.clearToken();
    this.clearRefreshToken();
    this.clearUser();
  },
};

// ── Refresh-on-401 ────────────────────────────────────────────────────────────

let refreshInFlight: Promise<string | null> | null = null;

/**
 * Attempt to rotate the refresh token. Returns the new access token on
 * success, or {@code null} if the refresh token is missing/invalid (which
 * means the user must re-authenticate).
 */
async function tryRefresh(): Promise<string | null> {
  const rt = authStorage.getRefreshToken();
  if (!rt) return null;
  try {
    // Use a bare Axios call (not the intercepted apiClient) to avoid recursion
    // if the /auth/refresh endpoint itself somehow returns 401.
    const baseURL = (apiClient.defaults.baseURL || '').replace(/\/$/, '');
    const res = await fetch(`${baseURL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
    });
    if (!res.ok) {
      authStorage.clearAll();
      return null;
    }
    const body = (await res.json()) as AuthSuccess;
    authStorage.setToken(body.token);
    authStorage.setRefreshToken(body.refreshToken, body.refreshExpiresAt);
    authStorage.setUser(body.user);
    return body.token;
  } catch {
    authStorage.clearAll();
    return null;
  }
}

/**
 * Install interceptors on the shared apiClient that:
 *   1. inject the bearer token when one is available;
 *   2. on a 401, attempt a single refresh-and-retry before giving up.
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
    async (err: AxiosError) => {
      const original = err.config as (typeof err.config & { _retry?: boolean }) | undefined;
      if (err.response?.status === 401 && original && !original._retry) {
        original._retry = true;
        // De-dupe concurrent refresh attempts
        if (!refreshInFlight) refreshInFlight = tryRefresh();
        const newToken = await refreshInFlight;
        refreshInFlight = null;
        if (newToken) {
          original.headers = original.headers ?? {};
          (original.headers as Record<string, string>).Authorization = `Bearer ${newToken}`;
          return client.request(original);
        }
        // Refresh failed — credentials cleared inside tryRefresh
      }
      return Promise.reject(err);
    },
  );
}
