import { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { hasPermission, hasRole } from '../services/auth';
import { LoadingState, ErrorState } from './ui/states';

interface ProtectedRouteProps {
  children: ReactNode;
  /**
   * If provided, the principal must hold at least one of the listed
   * platform role codes. Otherwise the user is redirected to the home
   * page with an error message visible in the layout header.
   */
  requireAnyRole?: string[];
  /**
   * If provided, the principal must hold at least one of the listed
   * permission codes. Used for the capability-scoped surfaces (health
   * dashboard, audit log, AI settings, analytics, notification
   * policies, integrations, governance).
   *
   * <p>The backend enforces the same matrix, so this is a courtesy
   * gate: it shows a clear message instead of a broken page that
   * fails on every request.
   */
  requireAnyPermission?: string[];
}

/**
 * The route an account with a temporary password is sent to.
 *
 * <p>The API refuses every request other than "read your profile" and
 * "change your password" while the flag is set, so the front-end has
 * to route the account owner there rather than let them click into a
 * page that would come back with a 403.
 */
export const CHANGE_PASSWORD_PATH = '/profile/change-password';

export function ProtectedRoute({
  children,
  requireAnyRole,
  requireAnyPermission,
}: ProtectedRouteProps) {
  const { isAuthenticated, initializing, user } = useAuth();
  const location = useLocation();

  if (initializing) {
    return <LoadingState message="Loading session…" />;
  }

  if (!isAuthenticated) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }

  // An account that still holds a temporary password can only change
  // it. Sending it anywhere else would be a page that fails on every
  // request, so it is redirected to the one screen it can use.
  if (user?.mustChangePassword && location.pathname !== CHANGE_PASSWORD_PATH) {
    return <Navigate to={CHANGE_PASSWORD_PATH} replace />;
  }

  if (requireAnyRole && requireAnyRole.length > 0) {
    const ok = requireAnyRole.some((code) => hasRole(user, code));
    if (!ok) {
      return (
        <ErrorState
          message={`This page requires one of these platform roles: ${requireAnyRole.join(', ')}.`}
        />
      );
    }
  }

  if (requireAnyPermission && requireAnyPermission.length > 0) {
    const ok = requireAnyPermission.some((code) => hasPermission(user, code));
    if (!ok) {
      return (
        <ErrorState
          message={`This page requires one of these permissions: ${requireAnyPermission.join(', ')}.`}
        />
      );
    }
  }

  return <>{children}</>;
}

interface GuestRouteProps {
  children: ReactNode;
}

export function GuestRoute({ children }: GuestRouteProps) {
  const { isAuthenticated, initializing } = useAuth();

  if (initializing) {
    return <LoadingState message="Loading session…" />;
  }

  if (isAuthenticated) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}

/**
 * Route wrapper for the OAuth callback page. Allows both authenticated
 * and guest visitors because:
 *  - The "login / register" flow lands here for new visitors.
 *  - The "link provider" flow (from /profile) lands here for users
 *    who are already signed in, with `?linked=` or `?error=` in the
 *    query string.
 */
export function AuthCallbackRoute({ children }: GuestRouteProps) {
  const { initializing } = useAuth();

  if (initializing) {
    return <LoadingState message="Loading session…" />;
  }

  return <>{children}</>;
}
