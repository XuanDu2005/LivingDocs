package com.livingdocs.modules.github.client;

/**
 * Authenticated GitHub user returned by {@code GET /user}.
 *
 * @param id          GitHub numeric user id (used to deduplicate connections)
 * @param login       GitHub login (e.g. "octocat")
 * @param name        display name (may be null)
 * @param email       primary email (may be null, may be private)
 * @param avatarUrl   profile image URL
 */
public record GithubUser(
        Long id,
        String login,
        String name,
        String email,
        String avatarUrl) {
}