package com.livingdocs.modules.integrations.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.livingdocs.modules.integrations.model.IntegrationEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Single row in the admin "Recent activity" feed for integrations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventDto(
        UUID id,
        UUID connectionId,
        String provider,
        String eventType,
        String action,
        String status,
        String errorMessage,
        String payloadSummary,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt
) {
    public static EventDto of(IntegrationEvent e) {
        return new EventDto(
                e.getId(),
                e.getConnectionId(),
                e.getProvider().name(),
                e.getEventType(),
                e.getAction(),
                e.getStatus().name(),
                e.getErrorMessage(),
                e.getPayloadSummary(),
                e.getReceivedAt(),
                e.getProcessedAt()
        );
    }
}