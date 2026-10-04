package com.livingdocs.modules.github.dto;

import java.util.List;

/**
 * Wraps a list of GitHub repositories returned by the catalog endpoint.
 * Returned by {@code GET /github/catalog/repositories} so the frontend
 * can present a picker for the user to select which repository to
 * connect to a workspace.
 */
public record GithubRepositoryListResponse(
        List<GithubRepositoryCatalogItem> repositories) {
}