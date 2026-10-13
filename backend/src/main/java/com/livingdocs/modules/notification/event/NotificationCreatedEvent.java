package com.livingdocs.modules.notification.event;

import com.livingdocs.modules.notification.model.Notification;

import java.util.UUID;

/**
 * In-process Spring event published every time a notification row is
 * persisted. The {@link NotificationStreamController} listens for
 * these and pushes the JSON payload to the user's active SSE
 * connection so the bell icon updates without polling.
 */
public class NotificationCreatedEvent {

    private final Notification notification;

    public NotificationCreatedEvent(Notification notification) {
        this.notification = notification;
    }

    public Notification getNotification() {
        return notification;
    }

    public UUID getUserId() {
        return notification.getUserId();
    }
}
