import apiClient from './api';

export interface AvatarUploadResponse {
  /** Relative storage key, e.g. `avatars/<user-id>/<uuid>.png`. */
  avatarKey: string | null;
  /** Publicly fetchable URL of the image, or `null` after removal. */
  avatarUrl: string | null;
}

/**
 * Uploads (or replaces) the signed-in user's profile picture.
 *
 * The backend validates the file type by its magic bytes, rescales it
 * to at most 512px and re-encodes it as PNG, so the client can send
 * any common image format.
 */
export async function uploadAvatar(file: File): Promise<AvatarUploadResponse> {
  const form = new FormData();
  form.append('file', file);

  // No explicit Content-Type header: the browser must add the multipart
  // boundary itself. Setting it by hand produces a body the server
  // cannot parse.
  const { data } = await apiClient.post<AvatarUploadResponse>('/users/me/avatar', form);
  return data;
}

/** Removes the signed-in user's profile picture. */
export async function removeAvatar(): Promise<AvatarUploadResponse> {
  const { data } = await apiClient.delete<AvatarUploadResponse>('/users/me/avatar');
  return data;
}

/**
 * Builds an absolute URL for an avatar path returned by the backend.
 *
 * The backend returns a root-relative path (e.g.
 * `/api/v1/users/avatar/<id>/<file>`). In development the frontend
 * runs on a different port than the API, so relative paths would 404
 * — this prefixes the API origin.
 */
export function resolveAvatarUrl(avatarUrl: string | null | undefined): string | null {
  if (!avatarUrl) return null;
  if (/^https?:\/\//i.test(avatarUrl)) return avatarUrl;

  const apiBase = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1';
  // Strip the trailing `/api/v1` so we can build an absolute URL to the
  // backend origin, not to an API sub-path.
  const origin = apiBase.replace(/\/api\/v1\/?$/, '');
  return `${origin}${avatarUrl.startsWith('/') ? '' : '/'}${avatarUrl}`;
}
