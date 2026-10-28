package com.livingdocs.common.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Default-type placeholder used by Spring Kafka's JsonDeserializer
 * when a producer doesn't embed a {@code __TypeId__} header.
 *
 * <p>Concrete event classes inherit from {@link AbstractEvent}; in
 * practice the producer always serializes the concrete subclass so
 * the consumer deserializes the right type. This class exists only
 * to give the deserializer a target when no header is present.
 */
public final class GenericEvent extends AbstractEvent {

    @JsonCreator
    public GenericEvent(@JsonProperty("eventId") UUID eventId,
                        @JsonProperty("workspaceId") UUID workspaceId,
                        @JsonProperty("correlationId") UUID correlationId,
                        @JsonProperty("occurredAt") OffsetDateTime occurredAt) {
        super(eventId, workspaceId, correlationId, occurredAt);
    }
}