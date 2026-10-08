package com.livingdocs.modules.notification.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.notification.dto.NotificationPolicyDto;
import com.livingdocs.modules.notification.dto.NotificationPolicyRequest;
import com.livingdocs.modules.notification.service.NotificationPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform-wide defaults for notification kinds. Only ADMINs can change
 * these; per-workspace overrides live behind
 * {@code /api/v1/workspaces/{id}/notification-policies}.
 */
@RestController
@RequestMapping("/api/v1/admin/notification-policies")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin · Notification policies",
        description = "Platform-wide defaults: enable / disable each notification event kind")
public class AdminNotificationPolicyController {

    private final NotificationPolicyService service;

    public AdminNotificationPolicyController(NotificationPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List platform defaults with their current enabled state")
    public List<NotificationPolicyDto> list() {
        return service.listPlatformDefaults();
    }

    @PutMapping("/{eventKind}")
    @Operation(summary = "Set the platform-wide default for a single event kind")
    public NotificationPolicyDto upsert(@PathVariable String eventKind,
                                         @Valid @RequestBody NotificationPolicyRequest req) {
        return service.upsertPlatformDefault(CurrentUser.requireId(), "ADMIN",
                eventKind, req.enabled());
    }
}