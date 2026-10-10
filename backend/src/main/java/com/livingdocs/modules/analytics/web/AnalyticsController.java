package com.livingdocs.modules.analytics.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.analytics.dto.AnalyticsResponse;
import com.livingdocs.modules.analytics.service.AnalyticsService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/analytics")
@Tag(name = "Analytics", description = "Workspace-level analytics dashboard")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final WorkspaceService workspaceService;

    public AnalyticsController(AnalyticsService analyticsService, WorkspaceService workspaceService) {
        this.analyticsService = analyticsService;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    @Operation(summary = "Get aggregated analytics for a workspace")
    public ResponseEntity<AnalyticsResponse> getAnalytics(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "30") int days) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, com.livingdocs.modules.workspace.model.WorkspaceRole.MEMBER);
        return ResponseEntity.ok(analyticsService.getAnalytics(workspaceId, days));
    }
}
