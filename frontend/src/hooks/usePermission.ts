import { useMemo } from 'react';

import { useAuth } from '../contexts/AuthContext';
import { hasPermission } from '../services/auth';
import { PermissionCode } from '../services/permissions';

/**
 * Returns true when the signed-in user holds at least one of the given
 * permission codes.
 *
 * <p>Used to hide features the user may not use (health dashboard,
 * audit log, AI settings, analytics, notification policies,
 * integrations, GitHub commit, governance). The backend remains the
 * authority — every one of these endpoints answers 403 regardless of
 * what the UI shows, so hiding is a convenience, not the control.
 *
 * <p>ADMIN passes everything, mirroring the backend matrix.
 */
export function usePermission(...codes: PermissionCode[]): boolean {
  const { user } = useAuth();
  const key = codes.join('|');
  return useMemo(
    () => hasPermission(user, ...(key ? key.split('|') : [])),
    [user, key],
  );
}

/**
 * Same as {@link usePermission} but for a list that is built at
 * runtime (e.g. a filtered nav array).
 */
export function usePermissionCheck(): (...codes: string[]) => boolean {
  const { user } = useAuth();
  return useMemo(() => (...codes: string[]) => hasPermission(user, ...codes), [user]);
}
