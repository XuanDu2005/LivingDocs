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

    /** Indexing job finished (success or failure). */
    public static final String INDEXING_DONE = "INDEXING_DONE";

    public static final List<String> ALL = List.of(
            ADMIN_BROADCAST,
            DRIFT_DETECTED,
            SCM_PR_EVENT,
            SCM_PUSH,
            SCM_ISSUE,
            SCM_RELEASE,
            JIRA_ISSUE_EVENT,
            REVIEW_ASSIGNED,
            INDEXING_DONE
    );
}