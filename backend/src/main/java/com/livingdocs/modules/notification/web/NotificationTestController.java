package com.livingdocs.modules.notification.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Diagnostic endpoint that emits a notification for the current
 * user via the {@link NotificationService} (which now also
 * publishes a Kafka {@code notification.send} event).
 *
 * <p>Used by the deployment smoke test to verify the event bus
 * end-to-end without having to trigger a real GitHub webhook.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications (diagnostic)")
public class NotificationTestController {

    private final NotificationService notificationService;

    public NotificationTestController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/test")
    @Operation(summary = "Send a test notification to the current user (dev only)")
    public String sendTest() {
        UUID userId = CurrentUser.requireId();
        notificationService.send(
                userId,
                "system.test",
                "Hello from the event bus",
                "This notification was routed through NotificationService → "
                        + "Kafka notification.send topic → NotificationEventListener.",
                "/");
        return "queued";
    }

    @PostMapping("/test/{userId}")
    @Operation(summary = "Send a test notification to an arbitrary user (dev only)")
    public String sendTestTo(@PathVariable UUID userId) {
        notificationService.send(
                userId,
                "system.test",
                "Hello from the event bus",
                "This notification was routed through NotificationService → "
                        + "Kafka notification.send topic → NotificationEventListener.",
                "/");
        return "queued";
    }
}
