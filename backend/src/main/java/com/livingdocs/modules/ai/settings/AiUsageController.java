package com.livingdocs.modules.ai.settings;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/ai-usage")
@Tag(name = "AI Usage & Limits", description = "Token budget and rate limit configuration")
public class AiUsageController {

    private final AiRateLimitService rateLimitService;
    private final WorkspaceService workspaceService;

    public AiUsageController(AiRateLimitService rateLimitService, WorkspaceService workspaceService) {
        this.rateLimitService = rateLimitService;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    @Operation(summary = "Get AI usage summary and limits for this workspace")
    public ResponseEntity<AiRateLimitService.UsageSummary> getSummary(@PathVariable UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MEMBER);
        return ResponseEntity.ok(rateLimitService.getSummary(workspaceId));
    }

    @PutMapping("/limits")
    @Operation(summary = "Update rate limits for this workspace (Manager only)")
    public ResponseEntity<AiRateLimitService.UsageSummary> updateLimits(
            @PathVariable UUID workspaceId,
            @RequestBody UpdateLimitsRequest req) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MANAGER);
        // Persist the new limits to workspace_settings.extra JSONB
        rateLimitService.updateLimits(workspaceId, req.dailyTokenLimit(), req.monthlyTokenLimit(),
                req.rateLimitPerMinute());
        return ResponseEntity.ok(rateLimitService.getSummary(workspaceId));
    }

    public record UpdateLimitsRequest(
            Long dailyTokenLimit,
            Long monthlyTokenLimit,
            Integer rateLimitPerMinute
    ) {}
}
