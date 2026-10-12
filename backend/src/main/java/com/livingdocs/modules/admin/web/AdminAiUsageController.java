package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.AdminAiUsageResponse;
import com.livingdocs.modules.admin.dto.DailyUsageRow;
import com.livingdocs.modules.admin.service.AdminAiUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-wide AI usage admin endpoints.
 *
 * <p>Provides the same data as {@code AiUsageController} but aggregated
 * across all workspaces, with per-workspace limit management for
 * budget enforcement.
 */
@RestController
@RequestMapping("/api/v1/admin/ai-usage")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin · AI Usage", description = "Cross-tenant AI token budget and rate-limit administration")
public class AdminAiUsageController {

    private final AdminAiUsageService adminAiUsageService;

    public AdminAiUsageController(AdminAiUsageService adminAiUsageService) {
        this.adminAiUsageService = adminAiUsageService;
    }

    @GetMapping("/chart")
    @Operation(summary = "Platform-wide 30-day daily token chart")
    public List<DailyUsageRow> platformChart() {
        return adminAiUsageService.platformDailyChart();
    }

    @GetMapping("/summary")
    @Operation(summary = "Platform-wide usage summary for stat cards")
    public Map<String, Object> summary() {
        AdminAiUsageService.PlatformSummary s = adminAiUsageService.platformSummary();
        return Map.of(
                "workspaceCount", s.workspaceCount(),
                "last30DaysTokens", s.last30DaysTokens(),
                "last30DaysRequests", s.last30DaysRequests(),
                "last30DaysCost", s.last30DaysCost(),
                "todayTokens", s.todayTokens()
        );
    }

    @GetMapping("/workspaces")
    @Operation(summary = "Per-workspace AI usage breakdown, sorted by 30-day consumption")
    public List<AdminAiUsageResponse> workspaceBreakdown() {
        return adminAiUsageService.workspaceBreakdown();
    }

    @GetMapping("/workspaces/{workspaceId}/chart")
    @Operation(summary = "Daily usage chart for a single workspace")
    public List<DailyUsageRow> workspaceChart(@PathVariable UUID workspaceId) {
        return adminAiUsageService.workspaceDailyChart(workspaceId);
    }

    @PutMapping("/workspaces/{workspaceId}/limits")
    @Operation(summary = "Set AI token limits for a workspace")
    public ResponseEntity<AdminAiUsageResponse> updateLimits(
            @PathVariable UUID workspaceId,
            @RequestBody UpdateLimitsRequest req) {
        return ResponseEntity.ok(adminAiUsageService.updateLimits(
                workspaceId,
                req.dailyLimit(),
                req.monthlyLimit(),
                req.rateLimitPerMinute()
        ));
    }

    public record UpdateLimitsRequest(Long dailyLimit, Long monthlyLimit, Integer rateLimitPerMinute) {}
}
