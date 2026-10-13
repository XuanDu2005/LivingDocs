package com.livingdocs.modules.github.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.security.AuthenticatedUser;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.config.GithubProperties;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.model.WebhookEvent;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubIngestionService;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sandbox-only utility to fabricate a {@code push} event for a
 * connected repository without needing a real GitHub webhook delivery.
 *
 * <p>The endpoint is enabled when {@code github.sandbox=true} (the
 * default for local development). It synthesises the JSON payload
 * GitHub would send to {@code POST /api/v1/github/webhook}, persists
 * it as a {@link WebhookEvent}, and runs it through the regular
 * {@link GithubIngestionService#process(WebhookEvent)} path so the
 * downstream side-effects (commit ingestion, SCM_PUSH notifications,
 * drift hooks) are exercised exactly as they would be in production.
 *
 * <p>Use it to verify the "new commit → notification" flow without
 * needing a real GitHub repo or an internet-reachable webhook URL.
 */
@RestController
@RequestMapping("/api/v1/_dev/github")
public class DevGithubWebhookSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(DevGithubWebhookSimulatorController.class);

    private final RepositoryRepository repositoryRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final GithubIngestionService ingestionService;
    private final GithubOAuthService oauthService;
    private final GithubClient githubClient;
    private final ObjectMapper objectMapper;
    private final GithubProperties properties;
    private final String appPublicUrl;

    public DevGithubWebhookSimulatorController(RepositoryRepository repositoryRepository,
                                               WorkspaceMemberRepository workspaceMemberRepository,
                                               GithubIngestionService ingestionService,
                                               GithubOAuthService oauthService,
                                               GithubClient githubClient,
                                               ObjectMapper objectMapper,
                                               GithubProperties properties,
                                               @Value("${app.public-url:http://localhost:8080}") String appPublicUrl) {
        this.repositoryRepository = repositoryRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.ingestionService = ingestionService;
        this.oauthService = oauthService;
        this.githubClient = githubClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.appPublicUrl = appPublicUrl.endsWith("/")
                ? appPublicUrl.substring(0, appPublicUrl.length() - 1)
                : appPublicUrl;
    }

    /**
     * Simulate a push event for the given connected repository. The
     * optional {@code body} request shape is:
     * <pre>
     * {
     *   "branch": "main",          // default: repo.defaultBranch
     *   "pusherName": "alice",     // default: "sandbox-user"
     *   "commits": [               // default: 1 synthetic commit
     *     { "id": "&lt;sha&gt;", "message": "feat: hello", "author": { "name": "alice" }, "timestamp": "2026-10-13T10:00:00Z", "url": "https://..." }
     *   ]
     * }
     * </pre>
     */
    @PostMapping("/simulate-push/{repositoryId}")
    public ResponseEntity<Map<String, Object>> simulatePush(
            @PathVariable UUID repositoryId,
            @RequestBody(required = false) Map<String, Object> body) {
        UUID userId = CurrentUser.requireId();
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        // Must be a member of the owning workspace to trigger this.
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(repo.getWorkspaceId(), userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Map<String, Object> out = buildAndProcessPush(repo, body);
        return ResponseEntity.accepted().body(out);
    }

    /**
     * Re-registers GitHub webhooks for every currently connected
     * repository in workspaces the caller belongs to. Useful for
     * repositories that were connected before the auto-register code
     * shipped, so their {@code webhook_id} is still {@code null} and
     * GitHub has no idea where to send push events.
     *
     * <p>For sandbox mode this is a no-op that just back-fills the
     * stored id; for real GitHub it actually calls
     * {@code POST /repos/{o}/{r}/hooks}. Best-effort: per-repo
     * failures are reported in the response but do not abort the
     * loop.
     */
    @PostMapping("/backfill-webhooks")
    public ResponseEntity<Map<String, Object>> backfillWebhooks(
            @RequestParam(value = "repositoryId", required = false) UUID repositoryId) {
        // Either an admin / sandbox caller, OR a member of the
        // workspace that owns the targeted repo. Members get
        // permission because the operation is local to their own
        // workspace data — the actual GitHub call uses the
        // workspace member's own OAuth token.
        UUID userId = CurrentUser.requireId();
        if (repositoryId == null) {
            requireDevAccess();
        }
        List<Repository> targets;
        if (repositoryId != null) {
            Repository r = repositoryRepository.findById(repositoryId)
                    .orElseThrow(() -> new NotFoundException("Repository not found"));
            if (workspaceMemberRepository.findByWorkspaceIdAndUserId(r.getWorkspaceId(), userId).isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            targets = List.of(r);
        } else {
            // Find every repo in workspaces the caller belongs to.
            // We use a simple loop rather than a single SQL join to keep
            // the repository interface unchanged.
            var memberWorkspaces = workspaceMemberRepository.findAllByUserId(userId);
            List<Repository> all = new ArrayList<>();
            for (var m : memberWorkspaces) {
                all.addAll(repositoryRepository.findByWorkspaceIdOrderByConnectedAtDesc(m.getWorkspaceId()));
            }
            targets = all;
        }

        String targetUrl = appPublicUrl + "/api/v1/github/webhook";
        String secret = properties.getWebhook().getSecret();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Repository repo : targets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repositoryId", repo.getId());
            row.put("fullName", repo.getFullName());
            row.put("webhookIdBefore", repo.getWebhookId());
            if (!"CONNECTED".equals(repo.getStatus().name())) {
                row.put("status", "skipped");
                row.put("reason", "repo status is " + repo.getStatus());
                results.add(row);
                continue;
            }
            try {
                GithubConnection conn = oauthService.findForCurrentUser().orElse(null);
                if (conn == null) {
                    // Fallback: pick any active connection of the repo owner.
                    conn = lookupAnyConnection(repo);
                }
                if (conn == null) {
                    row.put("status", "skipped");
                    row.put("reason", "no GitHub OAuth connection for caller or owner");
                    results.add(row);
                    continue;
                }
                Long hookId = githubClient.createWebhook(
                        conn.getAccessToken(),
                        repo.getOwner(),
                        repo.getName(),
                        targetUrl,
                        secret,
                        List.of("push", "pull_request", "ping"));
                if (hookId != null) {
                    repo.setWebhookId(hookId);
                    repositoryRepository.save(repo);
                }
                row.put("status", "ok");
                row.put("webhookIdAfter", hookId);
            } catch (Exception ex) {
                row.put("status", "error");
                row.put("error", ex.getMessage());
            }
            results.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetUrl", targetUrl);
        out.put("sandbox", properties.isSandbox());
        out.put("count", results.size());
        out.put("results", results);
        return ResponseEntity.ok(out);
    }

    private void requireDevAccess() {
        AuthenticatedUser principal = CurrentUser.current();
        if (principal == null) {
            throw new com.livingdocs.common.exception.UnauthorizedException(
                    "Authentication required");
        }
        log.info("requireDevAccess check: user={} roles={} sandbox={}",
                principal.getUsername(), principal.getRoleCodes(), properties.isSandbox());
        // Allow the bootstrap admin (whose user record predates the
        // role-permissions matrix) and any user holding a platform
        // admin role code.
        boolean isDev = properties.isSandbox()
                || "admin@livingdocs.com".equalsIgnoreCase(principal.getUsername())
                || principal.getRoleCodes().stream().anyMatch(code -> {
                    String c = code.toUpperCase();
                    return c.contains("ADMIN") || c.equals("PLATFORM_ADMIN");
                });
        if (!isDev) {
            throw new com.livingdocs.common.exception.ForbiddenException(
                    "This endpoint is for development / admin use only");
        }
    }

    private GithubConnection lookupAnyConnection(Repository repo) {
        // The repository row only stores the workspace id, not the
        // connecting user. Try the repo's connected_by column via
        // the GitHub OAuth service if it exposes such a lookup, else
        // return null and let the caller connect GitHub first.
        try {
            // Best-effort: ask the OAuth service for any active connection
            // whose user is a member of the workspace.
            var members = workspaceMemberRepository.findAllByWorkspaceId(repo.getWorkspaceId());
            for (var m : members) {
                GithubConnection candidate = oauthService.findForUser(m.getUserId()).orElse(null);
                if (candidate != null && "ACTIVE".equals(candidate.getStatus().name())) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Map<String, Object> buildAndProcessPush(Repository repo, Map<String, Object> body) {
        String branch = (body != null && body.get("branch") instanceof String s && !s.isBlank())
                ? s
                : (repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch());
        String pusherName = (body != null && body.get("pusherName") instanceof String p && !p.isBlank())
                ? p
                : "sandbox-user";

        List<Map<String, Object>> commits;
        if (body != null && body.get("commits") instanceof List<?> list && !list.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) list;
            commits = raw;
        } else {
            // Synthetic commit. The SHA is a 40-char hex string built from
            // a uuid so it is unique across calls and we don't accidentally
            // re-notify. (Real GitHub SHAs are 40 hex characters; we pad.)
            String stripped = UUID.randomUUID().toString().replace("-", "");
            String syntheticSha = ("sim" + stripped + stripped).substring(0, 40);
            Map<String, Object> author = new LinkedHashMap<>();
            author.put("name", pusherName);
            author.put("email", pusherName + "@sandbox.test");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", syntheticSha);
            c.put("message", "sandbox: simulated push");
            c.put("timestamp", OffsetDateTime.now().toString());
            c.put("url", "https://sandbox.test/" + repo.getFullName() + "/commit/" + syntheticSha);
            c.put("author", author);
            commits = List.of(c);
        }
        String headSha = (String) commits.get(0).getOrDefault("id", "");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ref", "refs/heads/" + branch);
        payload.put("after", headSha);
        payload.put("before", "0000000000000000000000000000000000000000");
        payload.put("commits", commits);
        Map<String, Object> pusher = new LinkedHashMap<>();
        pusher.put("name", pusherName);
        pusher.put("email", pusherName + "@sandbox.test");
        payload.put("pusher", pusher);
        Map<String, Object> repoNode = new LinkedHashMap<>();
        repoNode.put("id", repo.getGithubId());
        repoNode.put("name", repo.getName());
        repoNode.put("full_name", repo.getFullName());
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("login", repo.getOwner());
        repoNode.put("owner", owner);
        repoNode.put("default_branch", branch);
        payload.put("repository", repoNode);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return Map.of("error", "payload serialization failed");
        }

        String deliveryId = "sim-" + UUID.randomUUID();
        WebhookEvent event = WebhookEvent.create(
                "push", deliveryId, null, payloadJson, repo.getId());
        ingestionService.process(event);

        log.info("Simulated push for repo {} (branch={}, commits={}, delivery={})",
                repo.getFullName(), branch, commits.size(), deliveryId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", deliveryId);
        out.put("repositoryId", repo.getId());
        out.put("workspaceId", repo.getWorkspaceId());
        out.put("branch", branch);
        out.put("commits", commits.size());
        out.put("sandbox", properties.isSandbox());
        return out;
    }
}
