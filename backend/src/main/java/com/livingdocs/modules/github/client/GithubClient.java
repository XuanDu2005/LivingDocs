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

    /**
     * Lists commits for the given repository, newest first.
     *
     * @param since optional filter — only commits with {@code authored_at > since}
     */
    List<GithubCommit> listCommits(String accessToken, String owner, String repo, int perPage, java.time.OffsetDateTime since);

    /**
     * Fetches full metadata for a single repository (refreshes description,
     * default branch, visibility, etc.). Used by the real re-sync.
     */
    GithubRepositorySummary getRepository(String accessToken, String owner, String repo);

    /**
     * Registers a webhook on the given repository so GitHub will
     * deliver {@code push}, {@code pull_request} and {@code ping} events
     * to the platform. Returns the resulting webhook id, or {@code null}
     * if the client could not register one (e.g. the stub backend
     * cannot reach the real GitHub API).
     *
     * <p>The {@code targetUrl} should be the publicly reachable URL of
     * {@code POST /api/v1/github/webhook} (configured via
     * {@code app.public-url}). The shared secret from
     * {@code github.webhook.secret} is used to compute the
     * HMAC-SHA256 signature GitHub expects in {@code X-Hub-Signature-256}.
     */
    Long createWebhook(String accessToken, String owner, String repo,
                       String targetUrl, String secret, List<String> events);

    /**
     * Removes a previously-registered webhook by id. No-op if the id
     * is {@code null} or unknown.
     */
    void deleteWebhook(String accessToken, String owner, String repo, Long hookId);
}