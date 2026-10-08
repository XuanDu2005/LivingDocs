package com.livingdocs.modules.notification.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.notification.model.Notification;
import com.livingdocs.modules.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Send the same notification to every user in {@code userIds}.
     *
     * <p>Duplicate IDs are deduplicated so the same payload doesn't get
     * inserted twice for a user that appears in multiple targets
     * (e.g. they hold both the {@code STAFF} and {@code MANAGER} roles).
     * Returns the number of notifications that were actually persisted.
     */
    @Transactional
    public int sendBatch(Collection<UUID> userIds, String kind, String title,
                         String body, String link) {
        Set<UUID> unique = new HashSet<>(userIds);
        if (unique.isEmpty()) {
            return 0;
        }
        List<Notification> rows = new ArrayList<>(unique.size());
        for (UUID userId : unique) {
            rows.add(new Notification(userId, kind, title, body, link));
        }
        repository.saveAll(rows);
        return rows.size();
    }

    @Transactional(readOnly = true)
    public List<Notification> listRecent(int limit) {
        return repository.findAll(org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))
                .getContent();
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