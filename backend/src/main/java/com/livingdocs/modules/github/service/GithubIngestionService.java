package com.livingdocs.modules.github.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubPullRequest;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.model.WebhookEvent;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.UUID;

/**
 * Translates raw GitHub webhook payloads and pull-request listings
 * into the {@code pull_requests} table.
 */
@Service
public class GithubIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GithubIngestionService.class);

    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final GithubClient githubClient;
    private final ObjectMapper objectMapper;

    public GithubIngestionService(RepositoryRepository repositoryRepository,
                                  PullRequestRepository pullRequestRepository,
                                  GithubClient githubClient,
                                  ObjectMapper objectMapper) {
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.githubClient = githubClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatch a single webhook event to the appropriate handler.
     */
    @Transactional
    public void process(WebhookEvent event) {
        switch (event.getEventType()) {
            case "ping" -> log.info("Webhook ping received (delivery {})", event.getDeliveryId());
            case "pull_request" -> handlePullRequest(event);
            case "push" -> log.debug("Push event ignored for now (delivery {})", event.getDeliveryId());
            default -> log.debug("Ignoring webhook event type {}", event.getEventType());
        }
    }

    /**
     * Pulls PRs from GitHub and stores them idempotently. Used for the
     * "manually re-sync" endpoint.
     */
    @Transactional
    public List<PullRequest> ingestPullRequests(UUID repositoryId, String state) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalStateException("Repository not found: " + repositoryId));
        // Real implementation reads the access token from the GitHub
        // connection of the user who connected the repo. For Phase 2 we
        // accept any token; sandbox has no token check.
        List<GithubPullRequest> prs = githubClient.listPullRequests(
                "any", repo.getOwner(), repo.getName(), state);
        for (GithubPullRequest pr : prs) {
            upsert(repo, pr);
        }
        repo.setLastSyncedAt(OffsetDateTime.now());
        repositoryRepository.save(repo);
        return pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(repositoryId);
    }

    @Transactional(readOnly = true)
    public List<PullRequest> listForRepository(UUID repositoryId) {
        return pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(repositoryId);
    }

    private void handlePullRequest(WebhookEvent event) {
        JsonNode payload = parse(event);
        JsonNode prNode = payload.path("pull_request");
        if (prNode.isMissingNode() || prNode.isNull()) {
            log.debug("PR webhook without pull_request payload, ignoring (delivery {})",
                    event.getDeliveryId());
            return;
        }
        String owner = payload.path("repository").path("owner").path("login").asText(null);
        String name = payload.path("repository").path("name").asText(null);
        if (owner == null || name == null) {
            log.warn("PR webhook missing repository info (delivery {})", event.getDeliveryId());
            return;
        }
        Optional<Repository> repo = repositoryRepository.findByGithubId(
                payload.path("repository").path("id").asLong());
        if (repo.isEmpty()) {
            log.debug("PR webhook for unconnected repo {}/{}, ignoring", owner, name);
            return;
        }
        GithubPullRequest pr = new GithubPullRequest(
                prNode.path("number").asLong(),
                prNode.path("title").asText(""),
                prNode.path("state").asText("open"),
                prNode.path("user").path("login").asText(null),
                prNode.path("head").path("ref").asText(""),
                prNode.path("base").path("ref").asText(""),
                prNode.path("head").path("sha").asText(""),
                prNode.path("html_url").asText(null),
                prNode.path("draft").asBoolean(false),
                parseDate(prNode.path("created_at").asText(null)),
                parseDate(prNode.path("updated_at").asText(null)),
                parseDate(prNode.path("closed_at").asText(null)),
                parseDate(prNode.path("merged_at").asText(null)),
                List.of());
        upsert(repo.get(), pr);
    }

    private void upsert(Repository repo, GithubPullRequest pr) {
        Optional<PullRequest> existing = pullRequestRepository
                .findByRepositoryIdAndGithubPrNumber(repo.getId(), pr.number());
        PullRequest.State state = mapState(pr.state(), pr.mergedAt());
        if (existing.isPresent()) {
            existing.get().updateFrom(state, pr.title(), pr.headSha(), pr.isDraft(),
                    pr.updatedAt(), pr.closedAt(), pr.mergedAt());
        } else {
            pullRequestRepository.save(PullRequest.create(
                    repo.getId(),
                    pr.number(),
                    pr.title(),
                    state,
                    pr.authorLogin(),
                    pr.headRef(),
                    pr.baseRef(),
                    pr.headSha(),
                    pr.htmlUrl(),
                    pr.isDraft(),
                    pr.createdAt() != null ? pr.createdAt() : OffsetDateTime.now(),
                    pr.updatedAt() != null ? pr.updatedAt() : OffsetDateTime.now(),
                    pr.closedAt(),
                    pr.mergedAt()));
        }
    }

    private static PullRequest.State mapState(String state, OffsetDateTime mergedAt) {
        if (mergedAt != null) {
            return PullRequest.State.MERGED;
        }
        if ("closed".equalsIgnoreCase(state)) {
            return PullRequest.State.CLOSED;
        }
        return PullRequest.State.OPEN;
    }

    private static OffsetDateTime parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(text);
    }

    private JsonNode parse(WebhookEvent event) {
        try {
            return objectMapper.readTree(event.getPayload());
        } catch (Exception ex) {
            throw new IllegalStateException("Malformed webhook payload", ex);
        }
    }
}