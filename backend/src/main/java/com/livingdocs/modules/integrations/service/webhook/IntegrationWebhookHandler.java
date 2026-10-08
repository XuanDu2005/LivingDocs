package com.livingdocs.modules.integrations.service.webhook;

import com.livingdocs.modules.integrations.model.IntegrationEvent;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.service.IntegrationConnectionService;

import java.util.List;

/**
 * Strategy interface implemented by each provider-specific webhook
 * handler. The public {@code IntegrationWebhookController} dispatches to
 * the right handler based on the URL path and delegates signature
 * verification + persistence to the same template.
 */
public interface IntegrationWebhookHandler {

    IntegrationProvider provider();

    /**
     * Verify the request signature using the {@code secret} that was stored
     * on the integration connection. Throws
     * {@link com.livingdocs.common.exception.UnauthorizedException} on
     * mismatch.
     */
    void verifySignature(String rawBody, java.util.Map<String, String> headers, String secret);

    /**
     * Parse the raw body and produce a fan-out side effect. The handler
     * should be idempotent and tolerant of partial / malformed payloads
     * — webhook sources are notoriously inconsistent in the wild.
     */
    WebhookResult handle(String rawBody, java.util.Map<String, String> headers);

    /**
     * Holder for the side-effects produced by a handler. The event row is
     * always returned (so the controller can update its processing status).
     * Notifications are queued for delivery via
     * {@code IntegrationOutboundEvent}.
     */
    record WebhookResult(
            IntegrationEvent event,
            List<OutboundNotification> notifications
    ) {
        public static WebhookResult processed(IntegrationEvent e, List<OutboundNotification> n) {
            return new WebhookResult(e, n);
        }

        public static WebhookResult ignored(IntegrationEvent e, String reason) {
            e.markIgnored(reason);
            return new WebhookResult(e, List.of());
        }
    }

    /**
     * Single notification to fan-out to a list of user IDs. The webhook
     * controller passes each record to {@code NotificationService}.
     */
    record OutboundNotification(
            List<java.util.UUID> userIds,
            String kind,
            String title,
            String body,
            String link
    ) {
    }
}