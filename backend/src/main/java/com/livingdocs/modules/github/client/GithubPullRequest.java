package com.livingdocs.modules.github.client;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Subset of {@code GET /repos/{owner}/{repo}/pulls} that the
 * integration needs to populate the {@code pull_requests} table.
 */
public record GithubPullRequest(
        Long number,
        String title,
        String state,
        String authorLogin,
        String headRef,
        String baseRef,
        String headSha,
        String htmlUrl,
        boolean isDraft,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt,
        OffsetDateTime mergedAt,
        List<String> labels) {
}