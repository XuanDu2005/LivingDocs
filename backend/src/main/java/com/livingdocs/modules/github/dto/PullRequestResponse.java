package com.livingdocs.modules.github.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Public representation of an ingested pull request.
 */
public record PullRequestResponse(
        String id,
        String repositoryId,
        Long number,
        String title,
        String state,
        String authorLogin,
        String headBranch,
        String baseBranch,
        String headSha,
        String htmlUrl,
        boolean isDraft,
        OffsetDateTime openedAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt,
        OffsetDateTime mergedAt,
        OffsetDateTime lastIngestedAt) {
}