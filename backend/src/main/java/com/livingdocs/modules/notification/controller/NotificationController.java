package com.livingdocs.modules.notification.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.notification.model.Notification;
import com.livingdocs.modules.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for in-app notifications.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notifications", description = "In-app notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/notifications")
    @Operation(summary = "List the current user's notifications")
    public List<Notification> list(@RequestParam(defaultValue = "false") boolean onlyUnread) {
        return service.listForUser(CurrentUser.requireId(), onlyUnread);
    }

    @GetMapping("/notifications/unread-count")
    @Operation(summary = "Count the current user's unread notifications")
    public Map<String, Long> unreadCount() {
        return Map.of("count", service.unreadCount(CurrentUser.requireId()));
    }

    @PostMapping("/notifications/{notificationId}/read")
    @Operation(summary = "Mark a notification as read")
    public Notification read(@PathVariable UUID notificationId) {
        return service.markRead(CurrentUser.requireId(), notificationId);
    }
}