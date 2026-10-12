package com.livingdocs.modules.admin.model;

/**
 * When should documentation generation be triggered by default?
 *
 * <p>This is the platform-wide default copied into a new workspace's
 * {@code workspace_settings}; the workspace (or its per-template
 * overrides) can still flip to a different value.
 */
public enum GenerationTrigger {
    /** Generation only happens when a user explicitly asks for it. */
    MANUAL,
    /** Re-generate every time a new commit is pushed. */
    ON_COMMIT,
    /** Re-generate when a pull request is opened or updated (default). */
    ON_PULL_REQUEST,
    /** Re-generate when a pull request is merged. */
    ON_MERGE,
    /** Re-generate on a platform-wide schedule. */
    SCHEDULED
}
