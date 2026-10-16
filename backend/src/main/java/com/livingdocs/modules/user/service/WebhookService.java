package com.livingdocs.modules.user.service;

import com.livingdocs.modules.integrations.model.Webhook;
import com.livingdocs.modules.user.repository.WebhookRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WebhookService {

    private final WebhookRepository webhookRepository;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WebhookService(WebhookRepository webhookRepository, ObjectMapper objectMapper) {
        this.webhookRepository = webhookRepository;
        this.objectMapper = objectMapper;
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
                        
                System.out.println("Đã bắn webhook thành công tới: " + webhook.getName());
            } catch (Exception e) {
                System.err.println("Lỗi khi bắn webhook tới " + webhook.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Process incoming webhook events from GitHub.
     */
    public void processGitHubPayload(String eventType, String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);

        if ("push".equals(eventType)) {
            String repoName = json.path("repository").path("full_name").asText();
            String pusher = json.path("pusher").path("name").asText();

            // Key ngôn ngữ i18n để gửi xuống Frontend
            String notificationKey = "webhook.events.github_push";

            System.out.println(" [GitHub Webhook] " + pusher + " vừa push lên " + repoName + " (Key: " + notificationKey + ")");
            
        } else if ("issues".equals(eventType)) {
            String repoName = json.path("repository").path("full_name").asText();
            String sender = json.path("sender").path("login").asText();
            
            String notificationKey = "webhook.events.github_issue";
            
            System.out.println("[GitHub Webhook] " + sender + " vừa tạo/cập nhật issue trên " + repoName + " (Key: " + notificationKey + ")");
            
        } else {
            System.out.println("[GitHub Webhook] Đã nhận sự kiện: " + eventType);
        }
    }
}