import { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { hasRole } from '../services/auth';
import { LoadingState, ErrorState } from './ui/states';

interface ProtectedRouteProps {
  children: ReactNode;
  /**
   * If provided, the principal must hold at least one of the listed
   * platform role codes. Otherwise the user is redirected to the home
   * page with an error message visible in the layout header.
   */
  requireAnyRole?: string[];
}

export function ProtectedRoute({ children, requireAnyRole }: ProtectedRouteProps) {
  const { isAuthenticated, initializing, user } = useAuth();
  const location = useLocation();

  if (initializing) {
    return <LoadingState message="Loading session…" />;
  }

  if (!isAuthenticated) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
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