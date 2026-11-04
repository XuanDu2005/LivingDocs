package com.livingdocs.modules.notification.service;

import com.livingdocs.common.events.EventPublisher;
import com.livingdocs.common.events.NotificationSendEvent;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.notification.event.NotificationCreatedEvent;
import com.livingdocs.modules.notification.model.Notification;
import com.livingdocs.modules.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final NotificationPolicyService policyService;
    private final ApplicationEventPublisher eventPublisher;
    private final EventPublisher kafkaEventPublisher;
    private final NotificationPreferenceService preferenceService;

    public NotificationService(NotificationRepository repository,
                               NotificationPolicyService policyService,
                               ApplicationEventPublisher eventPublisher,
                               EventPublisher kafkaEventPublisher,
                               NotificationPreferenceService preferenceService) {
        this.repository = repository;
        this.policyService = policyService;
        this.eventPublisher = eventPublisher;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.preferenceService = preferenceService;
    }

    /**
     * Send an in-app notification to one user.
     *
     * <p>The per-user preferences are consulted first: a user who turned
     * this kind of notification off does not get the row at all. Kinds
     * without a dedicated preference (admin broadcasts, indexing jobs)
     * are always delivered.
     *
     * @return the persisted notification, or {@code null} when the
     *         user's preference suppresses it
     */
    @Transactional
    public Notification send(UUID userId, String kind, String title, String body, String link) {
        boolean inAppEnabled = preferenceService.isInAppEnabled(userId, kind);
        boolean emailEnabled = preferenceService.isEmailEnabled(userId, kind);

        if (!inAppEnabled && !emailEnabled) {
            log.debug("User {} turned off every channel for kind {} — dropping",
                    userId, kind);
            return null;
        }

        Notification saved = null;
        if (inAppEnabled) {
            Notification n = new Notification(userId, kind, title, body, link);
            saved = repository.save(n);
            eventPublisher.publishEvent(new NotificationCreatedEvent(saved));
        } else {
            log.debug("User {} turned off the in-app notification for kind {} — email only",
                    userId, kind);
        }

        // The in-app row is already persisted, so no IN_APP event is
        // published here. Publishing one would make the Kafka worker
        // call back into this method, which persists a *second* row and
        // publishes another IN_APP event — an infinite loop that filled
        // the notifications table with duplicates of every message.
        //
        // Only the email channel goes through the event bus: the delivery
        // worker owns it and this method stays agnostic to how (or
        // whether) it is wired up.
        if (emailEnabled) {
            publishEmail(userId, kind, title, body, link);
        }

        return saved;
    }

    /**
     * Publish the email-channel event for a notification.
     *
     * <p>Only published when the user actually asked for this kind of
     * notification by email — otherwise the worker would wake up for
     * every notification just to discard it.
     */
    private void publishEmail(UUID userId, String kind, String title, String body, String link) {
        if (!preferenceService.isEmailEnabled(userId, kind)) {
            return;
        }
        kafkaEventPublisher.publishNotification(new NotificationSendEvent(
                java.util.UUID.randomUUID(),
                null,
                null,
                OffsetDateTime.now(),
                userId,
                NotificationSendEvent.Channel.EMAIL,
                kind,
                title,
                body,
                link,
                java.util.Map.of()
        ));
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
        // The two channels are independent: a recipient may have turned
        // the in-app bell off but asked for email, or the other way round.
        // Filtering by in-app only would silently swallow the email too.
        List<UUID> inAppRecipients = unique.stream()
                .filter(userId -> preferenceService.isInAppEnabled(userId, kind))
                .toList();
        List<UUID> emailRecipients = unique.stream()
                .filter(userId -> preferenceService.isEmailEnabled(userId, kind))
                .toList();

        if (inAppRecipients.isEmpty() && emailRecipients.isEmpty()) {
            log.debug("Every recipient turned off every channel for kind {} — nothing sent",
                    kind);
            return 0;
        }

        int persisted = 0;
        if (!inAppRecipients.isEmpty()) {
            List<Notification> rows = new ArrayList<>(inAppRecipients.size());
            for (UUID userId : inAppRecipients) {
                rows.add(new Notification(userId, kind, title, body, link));
            }
            List<Notification> saved = repository.saveAll(rows);
            // Fan-out AFTER save so the SSE listener picks up rows that
            // already have id + created_at populated. We do this even
            // outside a transaction commit (the listener is @Async) — the
            // worst case is a duplicate "new" event for a row that ends
            // up rolled back, which the bell will reconcile on the next
            // poll cycle.
            for (Notification n : saved) {
                eventPublisher.publishEvent(new NotificationCreatedEvent(n));
            }
            persisted = saved.size();
        }

        for (UUID userId : emailRecipients) {
            publishEmail(userId, kind, title, body, link);
        }
        return persisted;
    }

    /**
     * Policy-aware variant of {@link #sendBatch}. The optional
     * {@code workspaceId} is used to consult
     * {@link NotificationPolicyService#isEnabled(UUID, String)}; if the
     * policy says the kind is disabled for that scope, no rows are
     * persisted and the call returns 0. Callers that don't care about
     * policies should still use {@link #sendBatch}.
     */
    @Transactional
    public int sendBatch(UUID workspaceId, Collection<UUID> userIds, String kind,
                         String title, String body, String link) {
        if (!policyService.isEnabled(workspaceId, kind)) {
            log.debug("Notification policy disabled for workspace={} kind={} — dropping send",
                    workspaceId, kind);
            return 0;
        }
        return sendBatch(userIds, kind, title, body, link);
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