package com.livingdocs.modules.github.client;

import com.livingdocs.modules.github.config.GithubProperties;
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
}