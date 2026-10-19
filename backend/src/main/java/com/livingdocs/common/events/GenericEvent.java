package com.livingdocs.common.events;

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
    public GenericEvent() {
        super(UUID.randomUUID(), null, null, OffsetDateTime.now());
    }
}
