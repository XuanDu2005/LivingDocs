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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GitHub webhook handler. Verifies the HMAC-SHA256 signature in
 * {@code X-Hub-Signature-256}, parses {@code pull_request} / {@code push}
 * / {@code issues} / {@code release} events, and translates them into
 * in-app notifications + outbound Slack mirrors.
 *
 * <p>This handler is intentionally stateless — connection lookup happens
 * lazily inside the controller and the {@code secret} is passed in once
 * the matching connection is found.
 */
@Component
public class GitHubWebhookHandler implements IntegrationWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookHandler.class);
    private static final String KIND_PR = "GITHUB_PR_EVENT";
    private static final String KIND_PUSH = "GITHUB_PUSH";
    private static final String KIND_ISSUE = "GITHUB_ISSUE_EVENT";
    private static final String KIND_RELEASE = "GITHUB_RELEASE";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionService connectionService;

    public GitHubWebhookHandler(ObjectMapper objectMapper,
                                IntegrationConnectionService connectionService) {
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
    }

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.GITHUB;
    }

    @Override
    public void verifySignature(String rawBody, Map<String, String> headers, String secret) {
        if (secret == null || secret.isBlank()) {
            // Without a configured secret we cannot verify; the controller
            // will have rejected it before reaching here. Defensive guard.
            throw new UnauthorizedException("No webhook secret configured for this connection");
        }
        String header = headers.get("x-hub-signature-256");
        if (header == null || !header.startsWith("sha256=")) {
            throw new UnauthorizedException("Missing or malformed X-Hub-Signature-256 header");
        }
        String expected = hmacSha256(rawBody == null ? "" : rawBody, secret);
        String provided = header.substring("sha256=".length());
        if (!constantTimeEquals(expected, provided)) {
            throw new UnauthorizedException("GitHub webhook signature mismatch");
        }
    }

    @Override
    @Transactional
    public WebhookResult handle(String rawBody, Map<String, String> headers) {
        String eventType = headers.getOrDefault("x-github-event", "");
        String deliveryId = headers.getOrDefault("x-github-delivery", UUID.randomUUID().toString());
        String action = headers.getOrDefault("x-github-action", null);

        if (eventType.isBlank()) {
            throw new UnauthorizedException("Missing X-GitHub-Event header");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception ex) {
            IntegrationEvent ev = connectionService.recordEvent(
                    IntegrationProvider.GITHUB, null, eventType, deliveryId, action, null);
            connectionService.finishEvent(ev, false, "Malformed JSON: " + ex.getMessage());
            throw new com.livingdocs.common.exception.BadRequestException("Malformed webhook body");
        }

        // Persist a summary row for the admin feed even before we decide
        // whether the event is interesting.
        String summaryJson = connectionService.toJsonString(summarize(root, eventType, action));
        IntegrationEvent eventRow = connectionService.recordEvent(
                IntegrationProvider.GITHUB, null, eventType, deliveryId, action, summaryJson);

        OutboundNotification notif;
        try {
            notif = switch (eventType) {
                case "pull_request" -> parsePullRequest(root);
                case "push" -> parsePush(root);
                case "issues" -> parseIssue(root);
                case "release" -> parseRelease(root);
                default -> null;
            };
        } catch (Exception ex) {
            log.warn("GitHub {} event processing failed: {}", eventType, ex.getMessage());
            connectionService.finishEvent(eventRow, false, ex.getMessage());
            return WebhookResult.processed(eventRow, List.of());
        }

        if (notif == null) {
            connectionService.ignoreEvent(eventRow, "No fan-out for event type " + eventType);
            return WebhookResult.processed(eventRow, List.of());
        }

        // The dispatcher performs the actual sendBatch once it knows the
        // workspace members; the handler just packages the side-effect.
        connectionService.finishEvent(eventRow, true, null);
        return WebhookResult.processed(eventRow, List.of(notif));
    }

    private Map<String, Object> summarize(JsonNode root, String eventType, String action) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventType", eventType);
        if (action != null) m.put("action", action);
        switch (eventType) {
            case "pull_request" -> {
                JsonNode pr = root.path("pull_request");
                m.put("number", root.path("number").asInt());
                m.put("title", pr.path("title").asText());
                m.put("author", pr.path("user").path("login").asText());
                m.put("repo", root.path("repository").path("full_name").asText());
            }
            case "push" -> {
                m.put("ref", root.path("ref").asText());
                m.put("repo", root.path("repository").path("full_name").asText());
                m.put("commits", root.path("commits").size());
            }
            case "issues" -> {
                m.put("action", root.path("action").asText());
                JsonNode issue = root.path("issue");
                m.put("number", issue.path("number").asInt());
                m.put("title", issue.path("title").asText());
                m.put("repo", root.path("repository").path("full_name").asText());
            }
            case "release" -> {
                m.put("tag", root.path("release").path("tag_name").asText());
                m.put("repo", root.path("repository").path("full_name").asText());
            }
            default -> {
                // No additional fields.
            }
        }
        return m;
    }

    private OutboundNotification parsePullRequest(JsonNode root) {
        String action = root.path("action").asText("opened");
        JsonNode pr = root.path("pull_request");
        String repo = root.path("repository").path("full_name").asText();
        int number = root.path("number").asInt();
        String title = pr.path("title").asText();
        String htmlUrl = pr.path("html_url").asText(null);
        String author = pr.path("user").path("login").asText("unknown");
        String text = String.format("PR #%d %s in %s by @%s: %s",
                number, action, repo, author, title);
        return new OutboundNotification(
                List.of(), KIND_PR, "GitHub: " + repo, text, htmlUrl
        );
    }

    private OutboundNotification parsePush(JsonNode root) {
        String ref = root.path("ref").asText();
        String repo = root.path("repository").path("full_name").asText();
        int commits = root.path("commits").size();
        String htmlUrl = root.path("repository").path("html_url").asText(null);
        String text = String.format("%d new commit(s) to %s in %s", commits, ref, repo);
        return new OutboundNotification(List.of(), KIND_PUSH, "GitHub: " + repo, text, htmlUrl);
    }

    private OutboundNotification parseIssue(JsonNode root) {
        String action = root.path("action").asText("opened");
        JsonNode issue = root.path("issue");
        String repo = root.path("repository").path("full_name").asText();
        int number = issue.path("number").asInt();
        String title = issue.path("title").asText();
        String htmlUrl = issue.path("html_url").asText(null);
        String text = String.format("Issue #%d %s in %s: %s", number, action, repo, title);
        return new OutboundNotification(List.of(), KIND_ISSUE, "GitHub: " + repo, text, htmlUrl);
    }

    private OutboundNotification parseRelease(JsonNode root) {
        JsonNode release = root.path("release");
        String tag = release.path("tag_name").asText();
        String repo = root.path("repository").path("full_name").asText();
        String htmlUrl = release.path("html_url").asText(null);
        String text = String.format("Release %s published in %s", tag, repo);
        return new OutboundNotification(List.of(), KIND_RELEASE, "GitHub: " + repo, text, htmlUrl);
    }

    // ---- HMAC helpers ----------------------------------------------------

    private static String hmacSha256(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}