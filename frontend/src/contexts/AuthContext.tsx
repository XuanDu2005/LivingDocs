import {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';

import { authStorage, AuthenticatedUser, describeError } from '../services/auth';
import {
  login as loginRequest,
  register as registerRequest,
  verifyEmail as verifyEmailRequest,
  LoginPayload,
  RegisterPayload,
  VerifyEmailPayload,
} from '../services/authApi';
import { fetchCurrentUser } from '../services/users';

interface AuthContextValue {
  user: AuthenticatedUser | null;
  initializing: boolean;
  isAuthenticated: boolean;
  login: (payload: LoginPayload) => Promise<void>;
  register: (payload: RegisterPayload) => Promise<{ requiresEmailVerification: boolean; email: string }>;
  verifyEmail: (payload: VerifyEmailPayload) => Promise<void>;
  logout: () => void;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [user, setUser] = useState<AuthenticatedUser | null>(authStorage.getUser());
  const [initializing, setInitializing] = useState<boolean>(
    Boolean(authStorage.getToken()),
  );

  const persist = useCallback((token: string, nextUser: AuthenticatedUser) => {
    authStorage.setToken(token);
    authStorage.setUser(nextUser);
    setUser(nextUser);
  }, []);

  const refreshUser = useCallback(async () => {
    try {
      const fresh = await fetchCurrentUser();
      authStorage.setUser(fresh);
      setUser(fresh);
    } catch (err) {
      // describeError keeps the message consistent with the rest of the app.
      // We don't surface this error to the UI — if the token is gone, the
      // interceptor has already cleared local credentials.
      describeError(err);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function bootstrap() {
      if (!authStorage.getToken()) {
        setInitializing(false);
        return;
      }
      try {
        const fresh = await fetchCurrentUser();
        if (!cancelled) {
          authStorage.setUser(fresh);
          setUser(fresh);
        }
      } catch {
        if (!cancelled) {
          authStorage.clearToken();
          authStorage.clearUser();
          setUser(null);
        }
      } finally {
        if (!cancelled) {
          setInitializing(false);
        }
      }
    }
    void bootstrap();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(
    async (payload: LoginPayload) => {
      const result = await loginRequest(payload);
      persist(result.token, result.user);
    },
    [persist],
  );

  const register = useCallback(
    async (payload: RegisterPayload) => {
      // /register no longer returns a token. The caller has to verify the
      // email first; we surface the result so the UI can navigate to the
      // verify-email page with the address in the query string.
      return registerRequest(payload);
    },
    [],
  );

  const verifyEmail = useCallback(
    async (payload: VerifyEmailPayload) => {
      const result = await verifyEmailRequest(payload);
      persist(result.token, result.user);
    },
    [persist],
  );

  const logout = useCallback(() => {
    authStorage.clearToken();
    authStorage.clearUser();
    setUser(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      initializing,
      isAuthenticated: user !== null,
      login,
      register,
      verifyEmail,
      logout,
      refreshUser,
    }),
    [user, initializing, login, register, verifyEmail, logout, refreshUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside an <AuthProvider>');
  }
  return ctx;
}