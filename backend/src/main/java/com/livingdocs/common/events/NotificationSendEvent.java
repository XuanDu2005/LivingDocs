package com.livingdocs.common.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Published whenever a notification should be delivered. The
 * notification worker (see {@code NotificationEventListener})
 * consumes these and routes them to the right channel:
 * in-app, email, Slack, etc.
 *
 * <p>Keeping delivery logic in a worker means the HTTP path that
 * emits the event stays fast (e.g. drift detection results
 * flowing to the user) and that channel-specific code (SMTP
 * setup, Slack API tokens) lives outside the request thread.
 */
public final class NotificationSendEvent extends AbstractEvent {

    public enum Channel { IN_APP, EMAIL, SLACK }

    private final UUID recipientUserId;
    private final Channel channel;
    private final String eventKind;        // e.g. "drift.detected", "doc.published"
    private final String title;
    private final String body;
    private final String link;
    private final Map<String, String> metadata;

    @JsonCreator
    public NotificationSendEvent(@JsonProperty("eventId") UUID eventId,
                                  @JsonProperty("workspaceId") UUID workspaceId,
                                  @JsonProperty("correlationId") UUID correlationId,
                                  @JsonProperty("occurredAt") OffsetDateTime occurredAt,
                                  @JsonProperty("recipientUserId") UUID recipientUserId,
                                  @JsonProperty("channel") Channel channel,
                                  @JsonProperty("eventKind") String eventKind,
                                  @JsonProperty("title") String title,
                                  @JsonProperty("body") String body,
                                  @JsonProperty("link") String link,
                                  @JsonProperty("metadata") Map<String, String> metadata) {
        super(eventId, workspaceId, correlationId, occurredAt);
        this.recipientUserId = recipientUserId;
        this.channel = channel;
        this.eventKind = eventKind;
        this.title = title;
        this.body = body;
        this.link = link;
        this.metadata = metadata == null ? Map.of() : metadata;
    }

    public UUID getRecipientUserId() { return recipientUserId; }
    public Channel getChannel() { return channel; }
    public String getEventKind() { return eventKind; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getLink() { return link; }
    public Map<String, String> getMetadata() { return metadata; }

    /** Convenience: copy with an additional metadata key. */
    public NotificationSendEvent withMetadata(String key, String value) {
        java.util.Map<String, String> next = new java.util.HashMap<>(metadata);
        next.put(key, value);
        return new NotificationSendEvent(getEventId(), getWorkspaceId(),
                getCorrelationId(), getOccurredAt(),
                recipientUserId, channel, eventKind, title, body, link, next);
    }
}