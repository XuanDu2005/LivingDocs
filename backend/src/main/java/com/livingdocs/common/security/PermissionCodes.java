package com.livingdocs.common.security;

/**
 * Canonical permission codes.
 *
 * <p>These strings are the contract between the database seed (see
 * {@code V36__capability_permissions.sql}), the
 * {@link RequirePermission} annotation, the service-layer guards and
 * the frontend. Keeping them in one place means a typo is a compile
 * error rather than a silently-refused request.
 */
public final class PermissionCodes {

    // ---- oversight -----------------------------------------------------
    /** Read the workspace health dashboard (document/drift/repo metrics). */
    public static final String HEALTH_DASHBOARD_VIEW = "HEALTH_DASHBOARD_VIEW";
    /** Read the aggregated analytics of a workspace. */
    public static final String ANALYTICS_VIEW = "ANALYTICS_VIEW";
    /** Read the governance audit trail of a workspace. */
    public static final String AUDIT_LOG_VIEW = "AUDIT_LOG_VIEW";

    // ---- governance ----------------------------------------------------
    /** Read the governance policy the runtime enforces. */
    public static final String GOVERNANCE_VIEW = "GOVERNANCE_VIEW";
    /** Change the governance knobs (threshold, merge policy, approval). */
    public static final String GOVERNANCE_MANAGE = "GOVERNANCE_MANAGE";

    // ---- AI ------------------------------------------------------------
    /** Read the AI provider configuration (provider, base URL, key mask). */
    public static final String AI_CONFIG_READ = "AI_CONFIG_READ";
    /** Change the AI provider configuration, including the API key. */
    public static final String AI_CONFIG_WRITE = "AI_CONFIG_WRITE";
    /** Read AI token consumption and quota dashboards. */
    public static final String AI_USAGE_VIEW = "AI_USAGE_VIEW";
    /** Change AI token budgets and rate limits. */
    public static final String AI_USAGE_MANAGE = "AI_USAGE_MANAGE";

    // ---- notifications -------------------------------------------------
    /** Read the resolved notification policies and delivery channels. */
    public static final String NOTIFICATION_POLICY_VIEW = "NOTIFICATION_POLICY_VIEW";
    /** Toggle notification policies and delivery channels. */
    public static final String NOTIFICATION_POLICY_MANAGE = "NOTIFICATION_POLICY_MANAGE";

    // ---- integrations --------------------------------------------------
    /** List the external accounts linked to a workspace. */
    public static final String INTEGRATION_VIEW = "INTEGRATION_VIEW";
    /** Connect a new external account. */
    public static final String INTEGRATION_CONNECT = "INTEGRATION_CONNECT";
    /** Disconnect an external account. */
    public static final String INTEGRATION_DISCONNECT = "INTEGRATION_DISCONNECT";
    /** Edit integration configuration (tokens, webhook URLs). */
    public static final String INTEGRATION_CONFIG = "INTEGRATION_CONFIG";

    // ---- repository ----------------------------------------------------
    /** Publish a document to GitHub as a branch + pull request. */
    public static final String SCM_COMMIT = "SCM_COMMIT";

    // ---- documents (existing catalogue) ---------------------------------
    public static final String DOCUMENT_CREATE = "DOCUMENT_CREATE";
    public static final String DOCUMENT_UPDATE = "DOCUMENT_UPDATE";
    public static final String DOCUMENT_DELETE = "DOCUMENT_DELETE";
    public static final String DOCUMENT_VERSION_REVIEW = "DOCUMENT_VERSION_REVIEW";
    public static final String DOCUMENT_VERSION_APPROVE = "DOCUMENT_VERSION_APPROVE";
    public static final String DOCUMENT_VERSION_PUBLISH = "DOCUMENT_VERSION_PUBLISH";
    public static final String AI_RUN_GENERATION = "AI_RUN_GENERATION";

    // ---- author capabilities (added by V37) ------------------------------
    /**
     * Dismiss (discard) an AI draft without publishing it.
     *
     * <p>Distinct from {@link #DOCUMENT_DELETE}: the document survives,
     * only the rejected draft version is set aside.
     */
    public static final String DOCUMENT_VERSION_DISMISS = "DOCUMENT_VERSION_DISMISS";
    /** Manually trigger an AI regeneration of a document. */
    public static final String DOCUMENT_REGENERATE_AI = "DOCUMENT_REGENERATE_AI";
    /**
     * Roll a document back to a previous version.
     *
     * <p>The spec asks the author to be able to undo an automatic
     * update that went wrong — that is a routine correction, not a
     * publishing decision, so it does not require the Manager tier.
     */
    public static final String DOCUMENT_ROLLBACK = "DOCUMENT_ROLLBACK";
    /** Toggle the AI auto-update of a repository. */
    public static final String REPOSITORY_AUTO_UPDATE_MANAGE = "REPOSITORY_AUTO_UPDATE_MANAGE";
    /** Toggle the AI auto-update of a module / package within a repository. */
    public static final String MODULE_AUTO_UPDATE_MANAGE = "MODULE_AUTO_UPDATE_MANAGE";
    /** Run a drift detection pass (manually or from a pull request). */
    public static final String DRIFT_DETECT_RUN = "DRIFT_DETECT_RUN";
    /** Read the repository access scopes granted to the GitHub connection. */
    public static final String GITHUB_SCOPE_VIEW = "GITHUB_SCOPE_VIEW";
    /** Change the repository access scopes of the GitHub connection. */
    public static final String GITHUB_SCOPE_MANAGE = "GITHUB_SCOPE_MANAGE";

    private PermissionCodes() {
        // constants only
    }
}
