package com.livingdocs.modules.notification.web;

import com.livingdocs.modules.notification.dto.NotificationChannelResponse;
import com.livingdocs.modules.notification.dto.UpdateNotificationChannelRequest;
import com.livingdocs.modules.notification.service.NotificationChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/notification-channels")
@Tag(name = "Notification Channels", description = "Per-workspace notification delivery channel configuration")
public class WorkspaceNotificationChannelController {

    private final NotificationChannelService service;

    public WorkspaceNotificationChannelController(NotificationChannelService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List notification channel configurations for this workspace")
    public ResponseEntity<List<NotificationChannelResponse>> list(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(service.listForWorkspace(workspaceId));
    }

    @PutMapping
    @Operation(summary = "Update a notification channel (Manager only)")
    public ResponseEntity<NotificationChannelResponse> update(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateNotificationChannelRequest req) {
        return ResponseEntity.ok(service.updateChannel(workspaceId, req));
    }
}
