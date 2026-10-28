package com.livingdocs.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
 *
 * <h2>Serialization contract</h2>
 * Events are immutable and have no default constructor, so every
 * subclass MUST expose a Jackson-visible creator. The canonical way
 * is to annotate the primary constructor with {@code @JsonCreator} and
 * every parameter with {@code @JsonProperty}. Because the parameter
 * names of a record are already reflected in the JSON, a plain
 * {@code @JsonCreator} on the canonical constructor is enough for
 * records — but the non-record subclasses below spell the properties
 * out explicitly so the wire format is stable even if the Java
 * parameter names are refactored.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is applied
 * everywhere so that adding a field to a producer never breaks an
 * older consumer that is still running (rolling deployments).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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