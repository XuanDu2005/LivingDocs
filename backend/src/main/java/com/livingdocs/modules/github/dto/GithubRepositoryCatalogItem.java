package com.livingdocs.modules.github.dto;

/**
 * One item of the GitHub repository catalog presented to the user.
 *
 * <p>This shape is independent of the persisted
 * {@code com.livingdocs.modules.github.model.Repository} entity because
 * catalog items are not yet connected.
 */
public record GithubRepositoryCatalogItem(
        Long githubId,
        String name,
        String fullName,
        String owner,
        String defaultBranch,
        String htmlUrl,
        String description,
        boolean isPrivate,
        boolean isArchived,
        boolean isDisabled) {
}