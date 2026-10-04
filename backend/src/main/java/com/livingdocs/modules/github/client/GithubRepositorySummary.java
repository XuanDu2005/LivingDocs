package com.livingdocs.modules.github.client;

/**
 * Subset of {@code GET /user/repos} that the integration cares about.
 */
public record GithubRepositorySummary(
        Long id,
        String name,
        String fullName,
        String ownerLogin,
        String defaultBranch,
        String htmlUrl,
        String description,
        boolean isPrivate,
        boolean isArchived,
        boolean isDisabled) {
}