package com.livingdocs.modules.github.client;

import java.util.List;

/**
 * Abstraction over the GitHub REST API.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link SandboxGithubClient} — deterministic stub used when
 *       {@code github.sandbox=true}, allowing the integration to be
 *       exercised end-to-end without real GitHub credentials.</li>
 *   <li>{@link RestGithubClient} — talks to {@code api.github.com} via
 *       WebClient when {@code github.sandbox=false}.</li>
 * </ul>
 */
public interface GithubClient {

    /**
     * Exchanges an OAuth code for an access token.
     *
     * @throws GithubClientException if the exchange fails
     */
    GithubTokenResponse exchangeCode(String code, String state);

    /**
     * Fetches the authenticated user for the given access token.
     */
    GithubUser getCurrentUser(String accessToken);

    /**
     * Lists repositories visible to the given access token.
     */
    List<GithubRepositorySummary> listUserRepositories(String accessToken, int perPage);

    /**
     * Lists pull requests for the given repository.
     */
    List<GithubPullRequest> listPullRequests(String accessToken, String owner, String repo, String state);

    /**
     * Fetches a single repository by its GitHub URL.
     *
     * @throws GithubClientException if the repo is not found or not accessible
     */
    GithubRepositorySummary fetchRepositoryByUrl(String accessToken, String url);
}