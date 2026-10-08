package com.livingdocs.modules.integrations.service.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.BadRequestException;
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
 * Slack Events API handler.
 *
 * <p>Slack verifies each request with a signing-secret based HMAC over
 * the raw body and the {@code X-Slack-Request-Timestamp} header.
 * Replays older than five minutes are rejected to mitigate replays.
 *
 * <p>The {@code url_verification} challenge is answered inline (Slack
 * expects the raw {@code challenge} value as the response body) — the
 * controller in this module is wired to return it for that case.
 */
@Component
public class SlackEventsHandler implements IntegrationWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackEventsHandler.class);
    private static final long MAX_SKEW_SECONDS = 60 * 5;

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionService connectionService;

    public SlackEventsHandler(ObjectMapper objectMapper,
                              IntegrationConnectionService connectionService) {
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
    }

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.SLACK;
    }

    @Override
    public void verifySignature(String rawBody, Map<String, String> headers, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new UnauthorizedException("No webhook secret configured for this connection");
        }
        String timestamp = headers.get("x-slack-request-timestamp");
        String signature = headers.get("x-slack-signature");
        if (timestamp == null || signature == null) {
            throw new UnauthorizedException("Missing Slack signing headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("Invalid Slack timestamp");
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) {
            throw new UnauthorizedException("Slack request timestamp outside the allowed window");
        }

        String base = "v0:" + timestamp + ":" + (rawBody == null ? "" : rawBody);
        String expected = computeHmac(base, secret);
        // The signature header is prefixed with "v0="; strip it before
        // comparing.
        String provided = signature.startsWith("v0=") ? signature.substring(3) : signature;
        if (!constantTimeEquals(expected, provided)) {
            throw new UnauthorizedException("Slack signature mismatch");
        }
    }

    @Override
    @Transactional
    public WebhookResult handle(String rawBody, Map<String, String> headers) {
        String deliveryId = headers.getOrDefault("x-slack-request-timestamp",
                UUID.randomUUID().toString());

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception ex) {
            IntegrationEvent stub = connectionService.recordEvent(
                    IntegrationProvider.SLACK, null, "unknown", deliveryId, null, null);
            connectionService.finishEvent(stub, false, "Malformed JSON: " + ex.getMessage());
            throw new BadRequestException("Malformed Slack payload");
        }

        String type = root.path("type").asText("unknown");

        // url_verification — answer with the challenge value verbatim.
        if ("url_verification".equals(type)) {
            IntegrationEvent ev = connectionService.recordEvent(
                    IntegrationProvider.SLACK, null, "url_verification", deliveryId, null, null);
            connectionService.finishEvent(ev, true, null);
            return WebhookResult.processed(ev, List.of());
        }

        String eventType = root.path("event").path("type").asText(type);
        Map<String, Object> summary = new HashMap<>();
        summary.put("type", type);
        summary.put("eventType", eventType);
        summary.put("team", root.path("team_id").asText());
        String summaryJson = connectionService.toJsonString(summary);

        IntegrationEvent ev = connectionService.recordEvent(
                IntegrationProvider.SLACK, null, eventType, deliveryId, null, summaryJson);
        // No notification fan-out for now — outbound Slack is one-way
        // for the MVP. Future: react to messages, commands, etc.
        connectionService.ignoreEvent(ev, "Inbound Slack events are not mirrored to notifications yet");
        return WebhookResult.processed(ev, List.of());
    }

    private static String computeHmac(String base, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute Slack signature", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}