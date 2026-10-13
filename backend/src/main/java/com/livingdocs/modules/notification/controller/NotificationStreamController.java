package com.livingdocs.modules.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.notification.event.NotificationCreatedEvent;
import com.livingdocs.modules.notification.model.Notification;
import com.livingdocs.modules.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events stream that pushes a JSON payload to the
 * connected client every time a {@link Notification} row is created
 * for that user. Used by the notification bell to update in real-time
 * without polling.
 *
 * <p>Connections live at {@code GET /api/v1/notifications/stream}.
 * Each authenticated user can have at most one active emitter; a new
 * request replaces the previous one. The emitter is also removed on
 * the standard Spring callbacks (timeout, completion, error) and on
 * the listener side whenever sending fails.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notifications")
public class NotificationStreamController {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamController.class);
    /** Connections idle longer than this are dropped by the client; we use the same number. */
    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L; // 30 min

    private final NotificationService service;
    private final ObjectMapper objectMapper;

    /**
     * Active emitters keyed by user id. We use a copy-on-write list
     * per user so that the listener can iterate without holding a
     * lock while doing I/O on a possibly-broken connection.
     */
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public NotificationStreamController(NotificationService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to a server-sent event stream of new notifications")
    public SseEmitter stream() {
        UUID userId = CurrentUser.requireId();
        // Replace any previous emitter for this user so the bell never
        // misses a "New" event because two tabs were open.
        List<SseEmitter> previous = emitters.get(userId);
        if (previous != null) {
            for (SseEmitter old : previous) {
                try {
                    old.complete();
                } catch (RuntimeException ignored) {
                }
            }
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        List<SseEmitter> slot = new CopyOnWriteArrayList<>();
        slot.add(emitter);
        emitters.put(userId, slot);

        Runnable cleanup = () -> {
            List<SseEmitter> list = emitters.get(userId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    emitters.remove(userId);
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        try {
            // First event — tells the client the stream is open.
            emitter.send(SseEmitter.event()
                    .name("hello")
                    .data(Map.of("userId", userId.toString())));
        } catch (IOException ex) {
            log.warn("Failed to send hello SSE event to user {}: {}", userId, ex.getMessage());
            cleanup.run();
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    /**
     * Push a notification to the matching emitter (if any). Hooked
     * on {@code AFTER_COMMIT} so we only fire the event when the
     * notification row is actually durable, and we never block the
     * writer on SSE I/O.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        UUID userId = event.getUserId();
        List<SseEmitter> slot = emitters.get(userId);
        if (slot == null || slot.isEmpty()) {
            return;
        }
        Notification n = event.getNotification();
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "id", n.getId().toString(),
                    "kind", n.getKind(),
                    "title", n.getTitle(),
                    "body", n.getBody() == null ? "" : n.getBody(),
                    "link", n.getLink() == null ? "" : n.getLink(),
                    "createdAt", n.getCreatedAt().toString()
            ));
            for (SseEmitter emitter : slot) {
                try {
                    emitter.send(SseEmitter.event().name("notification").data(payload));
                } catch (IOException | IllegalStateException ex) {
                    log.debug("SSE send failed for user {}: {}", userId, ex.getMessage());
                    slot.remove(emitter);
                }
            }
            if (slot.isEmpty()) {
                emitters.remove(userId);
            }
        } catch (Exception ex) {
            log.warn("Failed to serialise SSE payload for user {}: {}", userId, ex.getMessage());
        }
    }
}
