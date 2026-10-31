import axios, { AxiosInstance } from 'axios';

import { installAuthInterceptor } from './auth';

/**
 * Centralised Axios instance.
 *
 * All HTTP calls in the frontend should go through this client so that
 * the base URL, timeouts, and headers live in exactly one place.
 *
 * The base URL is driven by the `VITE_API_BASE_URL` environment variable
 * (see `.env.example`). It is inlined into the build at compile time.
 */
export const apiClient: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1',
  timeout: 10_000,
  headers: {
    // Deliberately no default Content-Type. Axios's default transform
    // would see "application/json" and serialise *every* request as
    // JSON — including FormData uploads, which would then be sent as a
    // JSON string instead of multipart. Leaving it unset lets Axios pick
    // the right content type per request: it still sets
    // `application/json` for plain objects, and `multipart/form-data`
    // (with the boundary it generates) for FormData.
    Accept: 'application/json',
  },
});

installAuthInterceptor(apiClient);

/**
 * Timeouts for calls that legitimately take longer than a normal API
 * round-trip.
 *
 * The default 10s is right for a simple read, but a few operations do
 * real work on the server and are not instant:
 *
 *   - `scan-docs` walks the repository's Markdown files, compares them
 *     against the indexed code, and then asks the AI to propose an
 *     update for every drifted file. On a repository with a handful of
 *     drifted files that is well over ten seconds.
 *   - `doc-sync/preview` performs one AI call.
 *
 * Without a longer budget these calls abort client-side while the
 * server is still working, and the user sees a bare "timeout" even
 * though the scan completes moments later.
 */
export const LONG_TIMEOUT_MS = 120_000;

export default apiClient;