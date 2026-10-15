package com.livingdocs.modules.integrations.controller;

import com.livingdocs.modules.integrations.model.Webhook;
import com.livingdocs.modules.integrations.repository.WebhookRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/workspaces/{workspaceId}/webhooks")
public class WebhookController {

    private final WebhookRepository webhookRepository;

    public WebhookController(WebhookRepository webhookRepository) {
        this.webhookRepository = webhookRepository;
    }

    // Retrieve the list of webhooks for a workspace.
    @GetMapping
    public ResponseEntity<List<Webhook>> getWebhooks(@PathVariable UUID workspaceId) {
        List<Webhook> webhooks = webhookRepository.findByWorkspaceIdAndIsActiveTrue(workspaceId);
        return ResponseEntity.ok(webhooks);
    }

    // Create a new webhook for a workspace.
    @PostMapping
    public ResponseEntity<Webhook> createWebhook(
            @PathVariable UUID workspaceId,
            @RequestBody Webhook webhook) {
        webhook.setWorkspaceId(workspaceId);
        Webhook savedWebhook = webhookRepository.save(webhook);
        return ResponseEntity.ok(savedWebhook);
    }
}