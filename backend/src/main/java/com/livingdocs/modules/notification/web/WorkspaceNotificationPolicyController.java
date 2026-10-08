package com.livingdocs.modules.notification.web;

import com.livingdocs.common.security.CurrentUser;
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
import java.util.UUID;

/**
 * Per-workspace overrides for notification policies. Members of a
 * workspace can read the list; only managers and above can toggle a
 * workspace row.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/notification-policies")
@Tag(name = "Workspace · Notification policies",
        description = "Per-workspace enable / disable for each notification event kind")
public class WorkspaceNotificationPolicyController {

    private final NotificationPolicyService service;

    public WorkspaceNotificationPolicyController(NotificationPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the resolved policies for this workspace")
    public List<NotificationPolicyDto> list(@PathVariable UUID workspaceId) {
        CurrentUser.requireId();
        return service.listForWorkspace(workspaceId);
    }

    @PutMapping("/{eventKind}")
    @Operation(summary = "Override the platform default for one event kind in this workspace (MANAGER+ only)")
    public NotificationPolicyDto upsert(@PathVariable UUID workspaceId,
                                         @PathVariable String eventKind,
                                         @Valid @RequestBody NotificationPolicyRequest req) {
        return service.upsertWorkspaceOverride(CurrentUser.requireId(), workspaceId,
                eventKind, req.enabled());
    }
}