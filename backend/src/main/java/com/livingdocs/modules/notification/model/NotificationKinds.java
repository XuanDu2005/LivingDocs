package com.livingdocs.modules.notification.model;

import java.util.List;

/**
 * Static registry of every {@link Notification} kind the platform emits.
 *
 * <p>This file intentionally avoids {@code @Component}-style indirection:
 * the list is short and stable, and the admin UI uses it to label rows
 * in the policies page. Adding a new kind means adding a constant here
 * and the corresponding call site that publishes it.
 */
public final class NotificationKinds {

    private NotificationKinds() {}

    /** Manual admin broadcast. Always enabled (managed separately). */
    public static final String ADMIN_BROADCAST = "ADMIN_BROADCAST";

    /** Drift detection found divergent documentation. */
    public static final String DRIFT_DETECTED = "drift.detected";

    /** SCM pull-request event (GitHub / GitLab). */
    public static final String SCM_PR_EVENT = "SCM_PR_EVENT";
    public static final String SCM_PUSH = "SCM_PUSH";
    public static final String SCM_ISSUE = "SCM_ISSUE_EVENT";
    public static final String SCM_RELEASE = "SCM_RELEASE";

    public static final String JIRA_ISSUE_EVENT = "JIRA_ISSUE_EVENT";

    /** Lifecycle event emitted by the review workflow. */
    public static final String REVIEW_ASSIGNED = "REVIEW_ASSIGNED";

    /**
     * The AI could not generate the documentation.
     *
     * <p>Emitted by the generation worker when the LLM call fails, so
     * the user learns about it without polling the job.
     */
    public static final String DOC_GENERATION_FAILED = "doc.generation.failed";

    /**
     * The GitHub connection lost its authorisation.
     *
     * <p>Emitted by the integration health check when GitHub answers
     * 401 for the stored token.
     */
    public static final String GITHUB_TOKEN_INVALID = "github.tokenInvalid";

    /**
     * The AI refreshed a document after a code change.
     */
    public static final String DOC_AUTO_UPDATED = "doc.auto.updated";

    /**
     * A review was requested on a document version.
     */
    public static final String REVIEW_REQUESTED = "review.requested";

    /**
     * A reviewer decided on a version the user had submitted.
     *
     * <p>Tells the author that their draft was approved, rejected, or
     * sent back for changes — without them having to watch the review
     * queue page.
     */
    public static final String REVIEW_DECIDED = "review.decided";

    /** Indexing job finished (success or failure). */
    public static final String INDEXING_DONE = "INDEXING_DONE";

    /**
     * A synthetic notification used to verify the delivery pipeline.
     */
    public static final String SYSTEM_TEST = "system.test";

    /**
     * Every kind the platform emits.
     *
     * <p>This list is the source of truth for the policies screens:
     * a kind that is emitted but missing here cannot be switched off
     * by an administrator, and the per-user preference that controls
     * it has nowhere to show up.
     */
    public static final List<String> ALL = List.of(
            ADMIN_BROADCAST,
            DRIFT_DETECTED,
            SCM_PR_EVENT,
            SCM_PUSH,
            SCM_ISSUE,
            SCM_RELEASE,
            JIRA_ISSUE_EVENT,
            REVIEW_ASSIGNED,
            REVIEW_REQUESTED,
            REVIEW_DECIDED,
            DOC_GENERATION_FAILED,
            GITHUB_TOKEN_INVALID,
            DOC_AUTO_UPDATED,
            INDEXING_DONE,
            SYSTEM_TEST
    );
}