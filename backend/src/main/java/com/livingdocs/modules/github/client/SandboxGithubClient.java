package com.livingdocs.modules.github.client;

import com.livingdocs.modules.github.config.GithubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Deterministic stub of the GitHub REST API.
 *
 * <p>Activated when {@code github.sandbox=true}. The stub returns a
 * fixed, in-memory data set so the OAuth flow, repository linking, and
 * webhook ingestion can all be tested locally without real GitHub
 * credentials. The data is keyed by the OAuth {@code code} value so
 * different sandbox runs produce different but stable identities.
 */
@Component
@ConditionalOnProperty(prefix = "github", name = "sandbox", havingValue = "true", matchIfMissing = true)
public class SandboxGithubClient implements GithubClient {

    private static final Logger log = LoggerFactory.getLogger(SandboxGithubClient.class);

    private final GithubProperties properties;

    public SandboxGithubClient(GithubProperties properties) {
        this.properties = properties;
    }

    @Override
    public GithubTokenResponse exchangeCode(String code, String state) {
        if (code == null || code.isBlank()) {
            return new GithubTokenResponse(null, null, null,
                    "invalid_grant", "code must be provided");
        }
        String token = "sandbox_token_" + Integer.toHexString(code.hashCode());
        String scope = properties.getOauth().getScopes();
        return new GithubTokenResponse(token, "bearer", scope, null, null);
    }

    @Override
    public GithubUser getCurrentUser(String accessToken) {
        long id = Math.abs((long) accessToken.hashCode() % 1_000_000L) + 100_000L;
        return new GithubUser(
                id,
                "sandbox-user-" + Long.toHexString(id),
                "Sandbox User " + id,
                "sandbox-" + id + "@example.test",
                "https://avatars.githubusercontent.com/u/0");
    }

    @Override
    public List<GithubRepositorySummary> listUserRepositories(String accessToken, int perPage) {
        return List.of(
                repo(1001L, "livingdocs", "acme/livingdocs", "main",
                        "LivingDocs monorepo for the AI-driven documentation platform.",
                        false),
                repo(1002L, "docs-portal", "acme/docs-portal", "main",
                        "Public developer portal and API reference.",
                        false),
                repo(1003L, "internal-tools", "acme/internal-tools", "main",
                        "Internal scripts and ops utilities.",
                        true),
                repo(1004L, "legacy-api", "acme/legacy-api", "main",
                        "Legacy REST API being phased out.",
                        false),
                repo(1005L, "experiments", "acme/experiments", "main",
                        "Throwaway prototypes and benchmarks.",
                        true)
        );
    }

    @Override
    public List<GithubPullRequest> listPullRequests(String accessToken, String owner, String repo, String state) {
        OffsetDateTime now = OffsetDateTime.now();
        return List.of(
                new GithubPullRequest(
                        42L,
                        "feat: add OAuth callback handler",
                        "open",
                        "alice",
                        "feat/oauth-callback",
                        "main",
                        "abcdef1234567890abcdef1234567890abcdef12",
                        "https://github.example/" + owner + "/" + repo + "/pull/42",
                        false,
                        now.minusDays(2), now.minusHours(3), null, null,
                        List.of("enhancement", "area/github")),
                new GithubPullRequest(
                        41L,
                        "fix: webhook signature verification edge case",
                        "open",
                        "bob",
                        "fix/webhook-verify",
                        "main",
                        "1234567890abcdef1234567890abcdef12345678",
                        "https://github.example/" + owner + "/" + repo + "/pull/41",
                        false,
                        now.minusDays(4), now.minusDays(1), null, null,
                        List.of("bug", "area/github")),
                new GithubPullRequest(
                        40L,
                        "chore: bump spring boot to 3.3.4",
                        "closed",
                        "carol",
                        "chore/spring-3.3.4",
                        "main",
                        "fedcba0987654321fedcba0987654321fedcba09",
                        "https://github.example/" + owner + "/" + repo + "/pull/40",
                        false,
                        now.minusDays(10), now.minusDays(8), now.minusDays(8), null,
                        List.of("dependencies"))
        );
    }

    private GithubRepositorySummary repo(long id, String name, String fullName, String branch,
                                          String description, boolean isPrivate) {
        return new GithubRepositorySummary(
                id, name, fullName,
                fullName.substring(0, fullName.indexOf('/')),
                branch,
                "https://github.example/" + fullName,
                description, isPrivate, false, false);
    }

    @Override
    public GithubRepositorySummary fetchRepositoryByUrl(String accessToken, String url) {
        // Parse owner/name from URL in sandbox mode
        String owner;
        String repoName;
        try {
            java.net.URI uri = new java.net.URI(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                throw new RuntimeException("Invalid URL");
            }
            path = path.replaceAll("^/+|/+$", "");
            String[] parts = path.split("/");
            if (parts.length < 2) {
                throw new RuntimeException("Invalid format");
            }
            owner = parts[0];
            repoName = parts[1];
        } catch (Exception e) {
            throw new com.livingdocs.modules.github.client.GithubClientException(
                    "Invalid GitHub URL format. Expected: https://github.com/owner/repo");
        }

        // Return a sandbox repo based on the parsed owner/repo
        return repo(
                (long) Math.abs((owner + repoName).hashCode()) % 10000 + 1000,
                repoName,
                owner + "/" + repoName,
                "main",
                "Repository fetched by URL (sandbox)",
                repoName.contains("private"));
    }

    @Override
    public List<GithubCommit> listCommits(String accessToken, String owner, String repo, int perPage, OffsetDateTime since) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime floor = since != null ? since : now.minusDays(30);
        return List.of(
                new GithubCommit(
                        "abc1234567890abcdef1234567890abcdef12345",
                        "feat: add V21 commit ingestion migration",
                        "Alice",
                        "alice@example.test",
                        now.minusHours(2), now.minusHours(2),
                        "https://github.example/" + owner + "/" + repo + "/commit/abc12345"),
                new GithubCommit(
                        "def2345678901bcdef2345678901bcdef2345678",
                        "fix: handle null pointer in webhook handler",
                        "Bob",
                        "bob@example.test",
                        now.minusDays(1), now.minusDays(1),
                        "https://github.example/" + owner + "/" + repo + "/commit/def23456"),
                new GithubCommit(
                        "789abcdef0123456789abcdef0123456789abcde",
                        "chore: bump dependencies",
                        "Carol",
                        "carol@example.test",
                        now.minusDays(2), now.minusDays(2),
                        "https://github.example/" + owner + "/" + repo + "/commit/789abcde")
        ).stream().filter(c -> c.authoredAt().isAfter(floor) || c.authoredAt().isEqual(floor)).toList();
    }

    @Override
    public GithubRepositorySummary getRepository(String accessToken, String owner, String repo) {
        return repo(
                (long) Math.abs((owner + repo).hashCode()) % 10000 + 1000,
                repo,
                owner + "/" + repo,
                "main",
                "Repository refreshed at " + OffsetDateTime.now(),
                false);
    }

    /**
     * Sandbox does not talk to a real GitHub instance, so there is no
     * webhook to register. Return a deterministic fake id so the
     * connection flow completes cleanly during local development.
     */
    @Override
    public Long createWebhook(String accessToken, String owner, String repo,
                             String targetUrl, String secret, List<String> events) {
        long id = 900_000L + (long) Math.abs((owner + "/" + repo).hashCode()) % 100_000L;
        log.info("Sandbox: would register webhook {} on {}/{} -> {}",
                id, owner, repo, targetUrl);
        return id;
    }

    @Override
    public void deleteWebhook(String accessToken, String owner, String repo, Long hookId) {
        log.info("Sandbox: would delete webhook {} on {}/{}", hookId, owner, repo);
    }

    @Override
    public String createFileAsPullRequest(String accessToken, String owner, String repo,
                                           String baseBranch, String headBranch,
                                           String filePath, String content,
                                           String commitMessage, String prTitle, String prBody) {
        // Sandbox mode just simulates a successful PR creation.
        log.info("Sandbox: would open PR on {}/{} from {} (file={}, {} bytes)",
                owner, repo, headBranch, filePath, content == null ? 0 : content.length());
        return "https://github.example/" + owner + "/" + repo + "/pull/sandbox-" + Math.abs((headBranch + filePath).hashCode() % 1000);
    }

    @Override
    public boolean branchExists(String accessToken, String owner, String repo, String branch) {
        return false; // sandbox never has pre-existing branches
    }

    @Override
    public void deleteBranch(String accessToken, String owner, String repo, String branch) {
        log.info("Sandbox: would delete branch {}/{}:{}", owner, repo, branch);
    }

    // ── Repository Contents ───────────────────────────────────────────────

    @Override
    public List<GithubRepositoryContent> listRepositoryContents(String accessToken, String owner, String repo,
                                                              String path, String ref) {
        log.info("Sandbox: listRepositoryContents {}/{}/{}", owner, repo, path);
        // Return a sample structure for docs folder
        if (path == null || path.isEmpty() || path.equals("docs")) {
            return List.of(
                    new GithubRepositoryContent("README.md", "docs/README.md", "file", 1024L, null),
                    new GithubRepositoryContent("API.md", "docs/API.md", "file", 2048L, null),
                    new GithubRepositoryContent("GUIDE.md", "docs/GUIDE.md", "file", 1536L, null),
                    new GithubRepositoryContent("api", "docs/api", "dir", null, null)
            );
        }
        return List.of();
    }

    @Override
    public String getFileContent(String accessToken, String owner, String repo, String path, String ref) {
        log.info("Sandbox: getFileContent {}/{}/{}", owner, repo, path);
        // Return sample content for known files
        if (path != null && path.endsWith(".md")) {
            return "# Sample Documentation\n\nThis is sample content for " + path;
        }
        return null;
    }
}