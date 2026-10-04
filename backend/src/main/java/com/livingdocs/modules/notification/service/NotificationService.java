package com.livingdocs.modules.notification.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.notification.model.Notification;
import com.livingdocs.modules.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight in-app notification service.
 *
 * <p>Notifications are produced by other services (drift detection, review
 * service, ...) and surfaced to the user via {@code /api/v1/notifications}.
 * For the MVP no external delivery (email/Slack/Zalo) is implemented —
 * that hooks in later through the same service.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Notification send(UUID userId, String kind, String title, String body, String link) {
        Notification n = new Notification(userId, kind, title, body, link);
        return repository.save(n);
    }

    @Transactional(readOnly = true)
    public List<Notification> listForUser(UUID userId, boolean onlyUnread) {
        return onlyUnread
                ? repository.findAllByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId)
                : repository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public Notification markRead(UUID userId, UUID notificationId) {
        Notification n = repository.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        if (!n.getUserId().equals(userId)) {
            throw new NotFoundException("Notification not found");
        }
        n.setReadAt(OffsetDateTime.now());
        return n;
    }
}