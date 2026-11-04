/**
 * Canonical permission codes, mirroring
 * {@code backend/src/main/java/com/livingdocs/common/security/PermissionCodes.java}
 * and the catalogue seeded by
 * {@code V16__role_permissions.sql} / {@code V36__capability_permissions.sql}.
 *
 * <p>The backend resolves these from the {@code role_permissions}
 * matrix and hands the effective set to the front-end through
 * {@code GET /api/v1/users/me}. The front-end uses them only to *hide*
 * features the user may not use — the backend remains the authority,
 * every one of these endpoints answers 403 regardless of what the UI
 * shows.
 */
export const PermissionCodes = {
  // ---- oversight -----------------------------------------------------
  HEALTH_DASHBOARD_VIEW: 'HEALTH_DASHBOARD_VIEW',
  ANALYTICS_VIEW: 'ANALYTICS_VIEW',
  AUDIT_LOG_VIEW: 'AUDIT_LOG_VIEW',

  // ---- governance ----------------------------------------------------
  GOVERNANCE_VIEW: 'GOVERNANCE_VIEW',
  GOVERNANCE_MANAGE: 'GOVERNANCE_MANAGE',

  // ---- AI ------------------------------------------------------------
  AI_CONFIG_READ: 'AI_CONFIG_READ',
  AI_CONFIG_WRITE: 'AI_CONFIG_WRITE',
  AI_USAGE_VIEW: 'AI_USAGE_VIEW',
  AI_USAGE_MANAGE: 'AI_USAGE_MANAGE',

  // ---- notifications -------------------------------------------------
  NOTIFICATION_POLICY_VIEW: 'NOTIFICATION_POLICY_VIEW',
  NOTIFICATION_POLICY_MANAGE: 'NOTIFICATION_POLICY_MANAGE',

  // ---- integrations --------------------------------------------------
  INTEGRATION_VIEW: 'INTEGRATION_VIEW',
  INTEGRATION_CONNECT: 'INTEGRATION_CONNECT',
  INTEGRATION_DISCONNECT: 'INTEGRATION_DISCONNECT',
  INTEGRATION_CONFIG: 'INTEGRATION_CONFIG',

  // ---- repository ----------------------------------------------------
  SCM_COMMIT: 'SCM_COMMIT',

  // ---- author capabilities (V37) --------------------------------------
  /** Dismiss (discard) an AI draft without publishing it. */
  DOCUMENT_VERSION_DISMISS: 'DOCUMENT_VERSION_DISMISS',
  /** Manually trigger an AI regeneration of a document. */
  DOCUMENT_REGENERATE_AI: 'DOCUMENT_REGENERATE_AI',
  /** Restore a document to a previous published version. */
  DOCUMENT_ROLLBACK: 'DOCUMENT_ROLLBACK',
  /** Toggle the AI auto-update of a repository. */
  REPOSITORY_AUTO_UPDATE_MANAGE: 'REPOSITORY_AUTO_UPDATE_MANAGE',
  /** Toggle the AI auto-update of a module / package. */
  MODULE_AUTO_UPDATE_MANAGE: 'MODULE_AUTO_UPDATE_MANAGE',
  /** Run a drift detection pass. */
  DRIFT_DETECT_RUN: 'DRIFT_DETECT_RUN',
  /** Review the repository access scopes of the GitHub connection. */
  GITHUB_SCOPE_VIEW: 'GITHUB_SCOPE_VIEW',
  /** Change the repository access scopes of the GitHub connection. */
  GITHUB_SCOPE_MANAGE: 'GITHUB_SCOPE_MANAGE',
} as const;

export type PermissionCode = (typeof PermissionCodes)[keyof typeof PermissionCodes];
