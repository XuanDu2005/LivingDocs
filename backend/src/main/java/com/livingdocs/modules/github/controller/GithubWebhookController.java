package com.livingdocs.modules.github.controller;

import com.livingdocs.modules.github.model.WebhookEvent;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Receives webhooks from GitHub.
 *
 * <p>The endpoint is publicly reachable (no JWT) but rejects requests
 * whose HMAC signature does not match the configured shared secret.
 * Configure the URL on the GitHub App / repository as
 * {@code https://<host>/api/v1/github/webhook} and select at least
 * {@code Pull requests} and {@code Push} events.
 */
@RestController
@RequestMapping("/api/v1/github")
@Tag(name = "GitHub Webhooks", description = "Inbound GitHub webhook receiver")
public class GithubWebhookController {

    private final GithubWebhookService webhookService;
    private final RepositoryRepository repositoryRepository;

    public GithubWebhookController(GithubWebhookService webhookService,
                                   RepositoryRepository repositoryRepository) {
        this.webhookService = webhookService;
        this.repositoryRepository = repositoryRepository;
    }

    @PostMapping("/webhook")
    @Operation(summary = "Receive a GitHub webhook (HMAC-verified)")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Hook-Installation-Target-ID", required = false) String installationTargetId,
            @RequestBody String rawBody) {

        // Try to find the matching repository for downstream processors.
        // For now we look it up by the GitHub id embedded in the payload.
        UUID repositoryId = extractRepositoryId(rawBody);
        webhookService.handle(eventType, deliveryId, signature, null, repositoryId, rawBody);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private UUID extractRepositoryId(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody);
            com.fasterxml.jackson.databind.JsonNode repo = node.path("repository");
            if (repo.isMissingNode()) {
                return null;
            }
            long githubId = repo.path("id").asLong(0L);
            if (githubId == 0L) {
                return null;
            }
            return repositoryRepository.findByGithubId(githubId)
                    .map(r -> r.getId())
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }
}