package com.livingdocs.modules.ai.settings;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
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
    @RequirePermission({"AI_USAGE_VIEW"})
    @Operation(summary = "Get AI usage summary and limits for this workspace")
    public ResponseEntity<AiRateLimitService.UsageSummary> getSummary(@PathVariable UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MEMBER);
        return ResponseEntity.ok(rateLimitService.getSummary(workspaceId));
    }

    @PutMapping("/limits")
    @RequirePermission({"AI_USAGE_MANAGE"})
    @Operation(summary = "Update rate limits for this workspace")
    public ResponseEntity<AiRateLimitService.UsageSummary> updateLimits(
            @PathVariable UUID workspaceId,
            @RequestBody UpdateLimitsRequest req) {
        UUID actor = CurrentUser.requireId();
        // The token budget and the rate limits are governance knobs:
        // they decide how much the workspace may spend and how fast it
        // may go. They are therefore guarded by the
        // documentation-governance guard — the same one the other
        // governance settings use — and not by the plain
        // workspace-manager requirement. The capability half of the rule
        // (AI_USAGE_MANAGE) is enforced by the permission interceptor on
        // the endpoint, so the two axes stay combined with AND.
        workspaceService.requireDocumentationGovernance(actor, workspaceId);
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
