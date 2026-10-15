package com.livingdocs.modules.github.client;

import java.util.List;

public interface GithubClient {

    // ── Inner DTOs ────────────────────────────────────────────────────────

    /**
     * Represents a file or directory entry returned by the GitHub Contents API.
     */
    record GithubRepositoryContent(
            String name,
            String path,
            String type,       // "file" or "dir"
            Long size,
            String downloadUrl
    ) {}

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

    /**
     * Lists the contents of a directory in a repository.
     *
     * @param accessToken GitHub access token
     * @param owner       repository owner
     * @param repo        repository name
     * @param path        directory path (e.g. "docs" or "docs/api")
     * @param ref         branch/tag/commit SHA (optional, defaults to default branch)
     * @return list of file/directory entries with name, path, type, and download URL
     */
    List<GithubRepositoryContent> listRepositoryContents(String accessToken, String owner, String repo,
                                                        String path, String ref);

    /**
     * Fetches the content of a single file.
     *
     * @param accessToken GitHub access token
     * @param owner       repository owner
     * @param repo        repository name
     * @param path        file path
     * @param ref         branch/tag/commit SHA (optional)
     * @return file content as decoded string, or null if not found
     */
    String getFileContent(String accessToken, String owner, String repo, String path, String ref);

    /**
     * Creates (or updates) a single file on a feature branch and opens a
     * pull request against the repository's default branch.
     *
     * <p>Implementation: from {@code baseBranch}, create a new branch
     * {@code headBranch}, {@code PUT /repos/{o}/{r}/contents/{path}}
     * on that branch, then {@code POST /repos/{o}/{r}/pulls} to open
     * a PR. Returns the resulting PR URL.
     *
     * @param accessToken GitHub access token
     * @param owner       repository owner
     * @param repo        repository name
     * @param baseBranch  the base branch to fork from (usually the default branch)
     * @param headBranch  the new feature branch to create
     * @param filePath    the file path inside the repo (e.g. {@code docs/foo.md})
     * @param content     UTF-8 text content to write
     * @param commitMessage commit message for the file change
     * @param prTitle     pull request title
     * @param prBody      pull request description (Markdown)
     * @return the resulting pull request URL on GitHub
     */
    String createFileAsPullRequest(String accessToken, String owner, String repo,
                                   String baseBranch, String headBranch,
                                   String filePath, String content,
                                   String commitMessage, String prTitle, String prBody);
}