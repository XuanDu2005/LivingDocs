package com.livingdocs.modules.github.client;

/**
 * One file touched by a pull request.
 *
 * <p>Mirrors the subset of {@code GET /repos/{owner}/{repo}/pulls/{number}/files}
 * the integration needs: the path, how it changed, and — where
 * available — the patch GitHub computed for it.
 */
public record GithubPullRequestFile(
        String path,
        /** {@code added}, {@code modified}, {@code removed} or {@code renamed}. */
        String status,
        /** The unified diff GitHub computed for this file, when it has one. */
        String patch,
        Long additions,
        Long deletions,
        Long changes) {
}
