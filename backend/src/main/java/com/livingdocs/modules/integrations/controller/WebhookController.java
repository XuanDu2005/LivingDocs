package com.livingdocs.modules.integrations.controller;

import com.livingdocs.modules.integrations.model.Webhook;
import com.livingdocs.modules.user.repository.WebhookRepository;
import com.livingdocs.modules.user.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookRepository webhookRepository;
    private final WebhookService webhookService;

    // Constructor injection for WebhookRepository and WebhookService
    public WebhookController(WebhookRepository webhookRepository, WebhookService webhookService) {
        this.webhookRepository = webhookRepository;
        this.webhookService = webhookService;
    }

    // Endpoint for GitHub to send events (push, issues, pull_request) to
     @PostMapping("/api/v1/integrations/webhook/github")
     public ResponseEntity<String> handleGitHubWebhook(
           @RequestHeader("X-GitHub-Event") String eventType,
           @RequestBody String payload) {
    
        try {
            log.info(" Nhận được sự kiện từ GitHub: {}", eventType);
            webhookService.processGitHubPayload(eventType, payload);
            return ResponseEntity.ok("Event accepted");
        } catch (Exception e) {
            log.error("Lỗi xử lý webhook", e);
            return ResponseEntity.badRequest().body("Payload error");
        }
    }

    // Retrieve the list of webhooks for a workspace (for use in the frontend interface)
    @GetMapping("/api/v1/workspaces/{workspaceId}/webhooks")
    public ResponseEntity<List<Webhook>> getWebhooks(@PathVariable UUID workspaceId) {
        List<Webhook> webhooks = webhookRepository.findByWorkspaceIdAndIsActiveTrue(workspaceId);
        return ResponseEntity.ok(webhooks);
    }

    // Create a new webhook for the workspace (for use in the frontend interface)
    @PostMapping("/api/v1/workspaces/{workspaceId}/webhooks")
    public ResponseEntity<Webhook> createWebhook(
            @PathVariable UUID workspaceId,
            @RequestBody Webhook webhook) {
        webhook.setWorkspaceId(workspaceId);
        Webhook savedWebhook = webhookRepository.save(webhook);
        return ResponseEntity.ok(savedWebhook);
    }
}