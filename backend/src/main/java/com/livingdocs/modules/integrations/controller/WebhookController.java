package com.livingdocs.modules.integrations.controller;

import com.livingdocs.modules.github.service.GithubWebhookService;
import com.livingdocs.modules.integrations.model.Webhook;
import com.livingdocs.modules.user.repository.WebhookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Generic outbound-webhook registry plus the inbound GitHub webhook
 * endpoint.
 *
 * <h2>Why the endpoint delegates to {@link GithubWebhookService}</h2>
 * There used to be a second, parallel GitHub webhook implementation in
 * {@code com.livingdocs.modules.user.service.WebhookService}. It had two
 * serious problems:
 * <ul>
 *   <li>It <b>did not verify the HMAC signature</b>, so anybody who
 *       could reach the endpoint could trigger an AI regeneration run.</li>
 *   <li>It used a <b>random UUID as the acting user</b>, which made the
 *       resulting document versions unattributable and broke the
 *       foreign key to {@code users}.</li>
 * </ul>
 * The endpoint now forwards to the signed, idempotent, properly
 * attributed implementation.
 */
@RestController
@CrossOrigin(origins = "*")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookRepository webhookRepository;
    private final GithubWebhookService githubWebhookService;

    public WebhookController(WebhookRepository webhookRepository,
                             GithubWebhookService githubWebhookService) {
        this.webhookRepository = webhookRepository;
        this.githubWebhookService = githubWebhookService;
    }

    /**
     * Inbound GitHub webhook.
     *
     * <p>The {@code X-Hub-Signature-256} header is required — the
     * signature is verified against the configured shared secret before
     * anything is processed. Requests without a valid signature get a
     * 401.
     */
    @PostMapping("/api/v1/integrations/webhook/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Repository-Id", required = false) String repositoryId,
            @RequestBody String payload) {

        log.info("Received GitHub webhook: event={} delivery={}", eventType, deliveryId);
        try {
            githubWebhookService.handle(
                    eventType,
                    deliveryId != null ? deliveryId : "no-delivery-" + UUID.randomUUID(),
                    signature,
                    null,
                    parseRepositoryId(repositoryId),
                    payload);
            return ResponseEntity.ok("Event accepted");
        } catch (com.livingdocs.common.exception.UnauthorizedException e) {
            // Signature verification failed — do not leak details.
            log.warn("Rejected GitHub webhook with invalid signature: {}", e.getMessage());
            return ResponseEntity.status(401).body("Invalid signature");
        } catch (Exception e) {
            log.error("Failed to process GitHub webhook", e);
            return ResponseEntity.badRequest().body("Payload error");
        }
    }

    /**
     * Retrieve the list of webhooks for a workspace (for use in the frontend interface)
     */
    @GetMapping("/api/v1/workspaces/{workspaceId}/webhooks")
    public ResponseEntity<List<Webhook>> getWebhooks(@PathVariable UUID workspaceId) {
        List<Webhook> webhooks = webhookRepository.findByWorkspaceIdAndIsActiveTrue(workspaceId);
        return ResponseEntity.ok(webhooks);
    }

    /**
     * Create a new webhook for the workspace (for use in the frontend interface)
     */
    @PostMapping("/api/v1/workspaces/{workspaceId}/webhooks")
    public ResponseEntity<Webhook> createWebhook(
            @PathVariable UUID workspaceId,
            @RequestBody Webhook webhook) {
        webhook.setWorkspaceId(workspaceId);
        Webhook savedWebhook = webhookRepository.save(webhook);
        return ResponseEntity.ok(savedWebhook);
    }

    private UUID parseRepositoryId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            // The header may carry GitHub's numeric repository id rather
            // than our internal UUID. The ingestion service resolves the
            // repository from the payload in that case.
            return null;
        }
    }
}
