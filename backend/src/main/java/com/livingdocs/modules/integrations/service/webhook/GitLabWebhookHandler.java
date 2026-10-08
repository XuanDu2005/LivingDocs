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
 * GitLab webhook handler. Verifies the {@code X-Gitlab-Token} constant
 * (shared token, NOT a HMAC) and parses the three event shapes that
 * matter to LivingDocs:
 *
 * <ul>
 *   <li>{@code Push Hook} — fires on every push to the repository</li>
 *   <li>{@code Merge Request Hook} — opens / updates / merges / closes MRs</li>
 *   <li>{@code Issue Hook} — opens / updates / closes issues</li>
 * </ul>
 */
@Component
public class GitLabWebhookHandler implements IntegrationWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookHandler.class);
    private static final String KIND_PUSH = "GITLAB_PUSH";
    private static final String KIND_MR = "GITLAB_MR_EVENT";
    private static final String KIND_ISSUE = "GITLAB_ISSUE_EVENT";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionService connectionService;

    public GitLabWebhookHandler(ObjectMapper objectMapper,
                                IntegrationConnectionService connectionService) {
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
    }

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.GITLAB;
    }

    @Override
    public void verifySignature(String rawBody, Map<String, String> headers, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new UnauthorizedException("No webhook secret configured for this connection");
        }
        String token = headers.get("x-gitlab-token");
        if (token == null) {
            throw new UnauthorizedException("Missing X-Gitlab-Token header");
        }
        if (!constantTimeEquals(token, secret)) {
            throw new UnauthorizedException("GitLab webhook token mismatch");
        }
    }

    @Override
    @Transactional
    public WebhookResult handle(String rawBody, Map<String, String> headers) {
        String eventType = headers.getOrDefault("x-gitlab-event", "");
        String deliveryId = headers.getOrDefault("x-gitlab-event-uuid",
                UUID.randomUUID().toString());
        String action = headers.getOrDefault("x-gitlab-event-action", null);

        if (eventType.isBlank()) {
            throw new UnauthorizedException("Missing X-Gitlab-Event header");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception ex) {
            IntegrationEvent stub = connectionService.recordEvent(
                    IntegrationProvider.GITLAB, null, eventType, deliveryId, action, null);
            connectionService.finishEvent(stub, false, "Malformed JSON: " + ex.getMessage());
            throw new com.livingdocs.common.exception.BadRequestException("Malformed webhook body");
        }

        String summaryJson = connectionService.toJsonString(summarize(root, eventType, action));
        IntegrationEvent eventRow = connectionService.recordEvent(
                IntegrationProvider.GITLAB, null, eventType, deliveryId, action, summaryJson);

        OutboundNotification notif = switch (eventType) {
            case "Push Hook" -> parsePush(root);
            case "Merge Request Hook" -> parseMergeRequest(root);
            case "Issue Hook" -> parseIssue(root);
            default -> null;
        };

        if (notif == null) {
            connectionService.ignoreEvent(eventRow, "No fan-out for event type " + eventType);
            return WebhookResult.processed(eventRow, List.of());
        }
        connectionService.finishEvent(eventRow, true, null);
        return WebhookResult.processed(eventRow, List.of(notif));
    }

    private Map<String, Object> summarize(JsonNode root, String eventType, String action) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventType", eventType);
        if (action != null) m.put("action", action);
        JsonNode project = root.path("project");
        m.put("project", project.path("path_with_namespace").asText(project.path("name").asText()));
        switch (eventType) {
            case "Push Hook" -> {
                m.put("ref", root.path("ref").asText());
                m.put("commits", root.path("commits").size());
            }
            case "Merge Request Hook" -> {
                JsonNode attrs = root.path("object_attributes");
                m.put("iid", attrs.path("iid").asInt());
                m.put("title", attrs.path("title").asText());
                m.put("state", attrs.path("state").asText());
            }
            case "Issue Hook" -> {
                JsonNode attrs = root.path("object_attributes");
                m.put("iid", attrs.path("iid").asInt());
                m.put("title", attrs.path("title").asText());
            }
            default -> {
                // no extra fields
            }
        }
        return m;
    }

    private OutboundNotification parsePush(JsonNode root) {
        String ref = root.path("ref").asText();
        String project = root.path("project").path("path_with_namespace").asText();
        int commits = root.path("commits").size();
        String url = root.path("project").path("web_url").asText(null);
        String text = String.format("%d new commit(s) to %s in %s", commits, ref, project);
        return new OutboundNotification(List.of(), KIND_PUSH, "GitLab: " + project, text, url);
    }

    private OutboundNotification parseMergeRequest(JsonNode root) {
        String action = root.path("object_attributes").path("action").asText("open");
        JsonNode attrs = root.path("object_attributes");
        int iid = attrs.path("iid").asInt();
        String title = attrs.path("title").asText();
        String project = root.path("project").path("path_with_namespace").asText();
        String url = attrs.path("url").asText(null);
        String text = String.format("MR !%d in %s: %s (%s)", iid, project, title, action);
        return new OutboundNotification(List.of(), KIND_MR, "GitLab: " + project, text, url);
    }

    private OutboundNotification parseIssue(JsonNode root) {
        String action = root.path("object_attributes").path("action").asText("open");
        JsonNode attrs = root.path("object_attributes");
        int iid = attrs.path("iid").asInt();
        String title = attrs.path("title").asText();
        String project = root.path("project").path("path_with_namespace").asText();
        String url = attrs.path("url").asText(null);
        String text = String.format("Issue #%d %s in %s: %s", iid, action, project, title);
        return new OutboundNotification(List.of(), KIND_ISSUE, "GitLab: " + project, text, url);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}