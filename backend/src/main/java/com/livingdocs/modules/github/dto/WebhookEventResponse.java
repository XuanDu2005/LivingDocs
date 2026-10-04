package com.livingdocs.modules.github.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public representation of a webhook event.
 */
public record WebhookEventResponse(
        UUID id,
        UUID repositoryId,
        String eventType,
        String action,
        String deliveryId,
        String processingStatus,
        String processingError,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt) {
}