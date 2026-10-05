import { useCallback, useEffect, useState } from 'react';

import { fetchBackendHealth, HealthStatus } from '../services/health';

interface HealthState {
  data: HealthStatus | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

/**
 * React hook that polls the backend health endpoint once on mount and
 * exposes a manual refresh function. Used by the dashboard placeholder
 * to demonstrate the centralised API configuration.
 */
export function useBackendHealth(): HealthState {
  const [data, setData] = useState<HealthStatus | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const status = await fetchBackendHealth();
      setData(status);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { data, loading, error, refresh };
}