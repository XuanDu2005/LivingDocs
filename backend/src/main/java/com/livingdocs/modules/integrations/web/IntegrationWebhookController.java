package com.livingdocs.modules.integrations.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.service.webhook.IntegrationWebhookDispatcher;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public webhook receiver for the integrations module.
 *
 * <p>These endpoints are intentionally NOT protected by the JWT filter
 * — they live behind the security config's
 * {@code permitAll()} list. Each provider authenticates the request
 * with its own mechanism (HMAC, shared token, signed timestamp) and the
 * dispatcher tries each configured connection's secret until one matches.
 *
 * <p>The URL pattern is {@code /api/v1/integrations/webhook/{provider}}
 * where {@code provider} matches {@link IntegrationProvider#name()}.
 * Slack's {@code url_verification} handshake is handled inline because
 * Slack expects the challenge value in the response body.
 */
@RestController
@RequestMapping("/api/v1/integrations/webhook")
@Tag(name = "Integration Webhooks", description = "Inbound webhooks from external services")
public class IntegrationWebhookController {

    private final IntegrationWebhookDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public IntegrationWebhookController(IntegrationWebhookDispatcher dispatcher,
                                        ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/github", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> github(@RequestBody String rawBody,
                                       @RequestHeader Map<String, String> headers) {
        dispatcher.dispatch(IntegrationProvider.GITHUB, rawBody, normaliseHeaders(headers));
        return ResponseEntity.accepted().build();
    }

    @PostMapping(value = "/gitlab", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> gitlab(@RequestBody String rawBody,
                                       @RequestHeader Map<String, String> headers) {
        dispatcher.dispatch(IntegrationProvider.GITLAB, rawBody, normaliseHeaders(headers));
        return ResponseEntity.accepted().build();
    }

    @PostMapping(value = "/jira", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> jira(@RequestBody String rawBody,
                                     @RequestHeader Map<String, String> headers) {
        dispatcher.dispatch(IntegrationProvider.JIRA, rawBody, normaliseHeaders(headers));
        return ResponseEntity.accepted().build();
    }

    @PostMapping(value = "/slack", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> slack(@RequestBody String rawBody,
                                          @RequestHeader Map<String, String> headers) {
        // Slack's url_verification handshake expects the raw challenge
        // echoed back as the response. Dispatcher still runs the handler
        // so the event is persisted, but we override the response body.
        dispatcher.dispatch(IntegrationProvider.SLACK, rawBody, normaliseHeaders(headers));
        try {
            JsonNode root = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
            JsonNode challenge = root.path("challenge");
            if (challenge.isTextual()) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.readTree("{\"challenge\":\"" + challenge.asText() + "\"}"));
            }
        } catch (Exception ignored) {
            // Fall through to plain OK.
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static Map<String, String> normaliseHeaders(Map<String, String> raw) {
        // Spring's @RequestHeader Map collapses multi-value headers to
        // the first value, which is what every downstream verifier wants.
        return raw;
    }
}