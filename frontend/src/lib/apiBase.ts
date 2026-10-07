/**
 * The origin of the backend, **without** any path prefix.
 *
 * <p>Used by full-page navigations (OAuth start) where we need to hit the
 * root rather than the JSON API. Everything else should keep using the
 * shared {@code apiClient} which already prefixes {@code /api/v1}.
 */
export const apiOrigin: string = (() => {
  const raw =
    (import.meta.env.VITE_API_BASE_URL as string | undefined) ??
    'http://localhost:8080/api/v1';
  // Strip a trailing /api/v1 (or any path) so we always end up at origin.
  try {
    const u = new URL(raw);
    return u.origin;
  } catch {
    return 'http://localhost:8080';
  }
})();
