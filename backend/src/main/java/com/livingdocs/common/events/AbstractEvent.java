package com.livingdocs.common.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Base contract for every event that flows through the LivingDocs
 * Kafka topics. Each subclass carries a {@code workspaceId} so the
 * consumer can short-circuit events for workspaces it doesn't own,
 * plus a {@code correlationId} that lets the platform trace a single
 * user request from HTTP entry all the way through async work.
 *
 * <p>Concrete event types live in the same package — keeping the
 * hierarchy flat avoids deep package paths and keeps JSON payloads
 * small. Consumers switch on the concrete type via Spring's
 * {@code @KafkaListener} method signatures.
 */
public abstract sealed class AbstractEvent
        permits DocGenerationRequestedEvent,
                DocGenerationCompletedEvent,
                DriftDetectionCompletedEvent,
                NotificationSendEvent,
                GenericEvent {

    private final UUID eventId;
    private final UUID workspaceId;
    private final UUID correlationId;
    private final OffsetDateTime occurredAt;

    protected AbstractEvent(UUID eventId,
                             UUID workspaceId,
                             UUID correlationId,
                             OffsetDateTime occurredAt) {
        this.eventId = eventId;
        this.workspaceId = workspaceId;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public UUID getEventId() { return eventId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getCorrelationId() { return correlationId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
