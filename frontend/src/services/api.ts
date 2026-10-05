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
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
});

installAuthInterceptor(apiClient);

export default apiClient;