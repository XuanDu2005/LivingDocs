package com.livingdocs.modules.integrations.service;

import com.livingdocs.modules.integrations.model.Webhook;
import com.livingdocs.modules.integrations.repository.WebhookRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WebhookService {

    private final WebhookRepository webhookRepository;
    private final RestClient restClient;

    public WebhookService(WebhookRepository webhookRepository) {
        this.webhookRepository = webhookRepository;
        // Initialize RestClient for making HTTP requests to Discord/Slack webhooks
        this.restClient = RestClient.create(); 
    }

    /**
     * Notify all active webhooks for a given workspace with a message.
     */
    public void notifyWorkspace(UUID workspaceId, String message) {
        // Fetch all active webhooks for the workspace
        List<Webhook> activeWebhooks = webhookRepository.findByWorkspaceIdAndIsActiveTrue(workspaceId);
        
        if (activeWebhooks.isEmpty()) {
            return;
        }

        // Create a payload to send to Discord/Slack.
        Map<String, String> payload = Map.of(
                "content", message,
                "text", message
        );

        // Go through each URL and "fire off" a message.
        for (Webhook webhook : activeWebhooks) {
            try {
                restClient.post()
                        .uri(webhook.getUrl())
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                        
                System.out.println("✅ Đã bắn webhook thành công tới: " + webhook.getName());
            } catch (Exception e) {
                System.err.println("❌ Lỗi khi bắn webhook tới " + webhook.getName() + ": " + e.getMessage());
            }
        }
    }
}