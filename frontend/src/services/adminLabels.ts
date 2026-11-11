import { TFunction } from 'i18next';

/**
 * Resolve a localized name/description pair for a role using the
 * {@code roleLabels.<CODE>} i18n namespace, with a graceful fallback to
 * whatever the API returned. The fallback is important because
 *
 *   1. the catalogue may contain custom roles seeded in a future
 *      migration that this build doesn't know about, and
 *   2. role names/descriptions are editable, so the API value is
 *      always the source of truth.
 */
export function roleName(code: string, fallback: string, t: TFunction): string {
  return t(`roleLabels.${code}.name`, { defaultValue: fallback });
}

/**
 * Whether a role's stored presentation has been customised by an
 * administrator.
 *
 * <p>The `roleLabels.*` i18n namespace carries the seeded,
 * translated name of every built-in role. Once an administrator
 * edits a role through the admin console, the stored value diverges
 * from the seed — and the i18n label would then silently mask the
 * edit, showing the administrator the old name for a role they just
 * renamed.
 *
 * <p>Comparing the two is enough to detect that: the seeded name is
 * the one the translation was written for, so a difference means the
 * stored value was changed.
 */
export function isRoleCustomised(
  code: string,
  storedName: string | null | undefined,
  t: TFunction,
): boolean {
  if (!storedName) return false;
  const localised = t(`roleLabels.${code}.name`, { defaultValue: '' });
  if (!localised) return false;
  return localised !== storedName;
}

export function roleDescription(
  code: string,
  fallback: string | null | undefined,
  t: TFunction,
): string {
  if (fallback == null) return '—';
  return t(`roleLabels.${code}.description`, { defaultValue: fallback });
}

/**
 * Localized description for an audit retention policy. The
 * {@code entityType} value (e.g. "audit_log") is the canonical key
 * coming from the backend, so the same fallback rules apply.
 */
export function retentionDescription(
  entityType: string,
  fallback: string | null | undefined,
  t: TFunction,
): string {
  if (fallback == null) return '—';
  return t(`retentionLabels.${entityType}.description`, { defaultValue: fallback });
}