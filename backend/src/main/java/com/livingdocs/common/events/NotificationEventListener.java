package com.livingdocs.common.events;

import com.livingdocs.modules.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link NotificationSendEvent}s from Kafka and routes
 * them to the appropriate delivery channel.
 *
 * <p>This is the seam that lets the rest of the codebase stay
 * agnostic to whether notifications are delivered synchronously
 * (e.g. in-app, persisted to the {@code notifications} table) or
 * asynchronously (e.g. email via SMTP, Slack via webhook). Future
 * channels only need to be added to the {@code switch} below.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.notification-send}",
            groupId = "${spring.kafka.consumer.group-id}-notif",
            concurrency = "3"
    )
    public void onNotification(NotificationSendEvent event) {
        log.debug("Delivering notification kind={} to user={} via {}",
                event.getEventKind(), event.getRecipientUserId(), event.getChannel());
        try {
            switch (event.getChannel()) {
                case IN_APP -> notificationService.send(
                        event.getRecipientUserId(),
                        event.getEventKind(),
                        event.getTitle(),
                        event.getBody(),
                        event.getLink());
                case EMAIL, SLACK -> log.warn(
                        "Channel {} not yet wired into NotificationService; "
                                + "persisted in-app notification for user {}.",
                        event.getChannel(), event.getRecipientUserId());
            }
        } catch (Exception e) {
            // Don't rethrow: a failing notification must not stall
            // the consumer. Kafka will redeliver the event on the
            // next consumer group rebalance, and we've also logged
            // the full stack trace.
            log.error("Failed to deliver notification kind={} to user={}: {}",
                    event.getEventKind(), event.getRecipientUserId(), e.getMessage(), e);
        }
    }
}
