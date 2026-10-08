package com.livingdocs.modules.integrations.service.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.UnauthorizedException;
import com.livingdocs.modules.integrations.model.IntegrationEvent;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.service.IntegrationConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jira webhook handler. Verification is a shared-token constant compare
 * via the {@code X-Atlassian-Webhook-Identifier} (or
 * {@code X-Hub-Signature} when present). The body uses Atlassian's
 * Connect event envelope.
 */
@Component
public class JiraWebhookHandler implements IntegrationWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(JiraWebhookHandler.class);
    private static final String KIND_ISSUE = "JIRA_ISSUE_EVENT";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionService connectionService;

    public JiraWebhookHandler(ObjectMapper objectMapper,
                              IntegrationConnectionService connectionService) {
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
    }

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.JIRA;
    }

    @Override
    public void verifySignature(String rawBody, Map<String, String> headers, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new UnauthorizedException("No webhook secret configured for this connection");
        }
        // Jira Cloud webhooks are authenticated either by a shared
        // header (recommended) or by an Atlassian-Connect JWT (out of
        // scope for this MVP). We accept either constant-time match.
        String provided = firstNonBlank(
                headers.get("x-atlassian-webhook-identifier"),
                headers.get("x-hub-signature"));
        if (provided == null) {
            throw new UnauthorizedException("Missing Jira webhook auth header");
        }
        if (!constantTimeEquals(provided, secret)) {
            throw new UnauthorizedException("Jira webhook token mismatch");
        }
    }

    @Override
    @Transactional
    public WebhookResult handle(String rawBody, Map<String, String> headers) {
        String eventType = headers.getOrDefault("x-atlassian-webhook-identifier", "jira:unknown");
        String deliveryId = headers.getOrDefault("x-request-uuid",
                UUID.randomUUID().toString());

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception ex) {
            IntegrationEvent stub = connectionService.recordEvent(
                    IntegrationProvider.JIRA, null, eventType, deliveryId, null, null);
            connectionService.finishEvent(stub, false, "Malformed JSON: " + ex.getMessage());
            throw new com.livingdocs.common.exception.BadRequestException("Malformed webhook body");
        }

        String webhookEvent = root.path("webhookEvent").asText(eventType);
        String summaryJson = connectionService.toJsonString(summarize(root));
        IntegrationEvent eventRow = connectionService.recordEvent(
                IntegrationProvider.JIRA, null, webhookEvent, deliveryId, null, summaryJson);

        OutboundNotification notif = parse(root, webhookEvent);
        if (notif == null) {
            connectionService.ignoreEvent(eventRow, "No fan-out for webhook event " + webhookEvent);
            return WebhookResult.processed(eventRow, List.of());
        }
        connectionService.finishEvent(eventRow, true, null);
        return WebhookResult.processed(eventRow, List.of(notif));
    }

    private Map<String, Object> summarize(JsonNode root) {
        Map<String, Object> m = new HashMap<>();
        m.put("webhookEvent", root.path("webhookEvent").asText());
        JsonNode issue = root.path("issue");
        if (!issue.isMissingNode()) {
            m.put("key", issue.path("key").asText());
            m.put("summary", issue.path("fields").path("summary").asText());
        }
        return m;
    }

    private OutboundNotification parse(JsonNode root, String webhookEvent) {
        if (!webhookEvent.startsWith("jira:issue_")) {
            return null;
        }
        JsonNode issue = root.path("issue");
        String key = issue.path("key").asText();
        String summary = issue.path("fields").path("summary").asText("");
        String self = issue.path("self").asText(null);
        String browserUrl = root.path("issue").path("fields").path("issuetype").path("iconUrl").isMissingNode()
                ? null : self;
        String action = webhookEvent.replaceFirst("jira:issue_", "");
        String text = String.format("Issue %s %s: %s", key, action, summary);
        return new OutboundNotification(List.of(), KIND_ISSUE, "Jira: " + key, text, browserUrl);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}