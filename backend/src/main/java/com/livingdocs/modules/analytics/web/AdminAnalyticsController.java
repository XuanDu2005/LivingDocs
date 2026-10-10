package com.livingdocs.modules.analytics.web;

import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.analytics.dto.AdminAnalyticsResponse;
import com.livingdocs.modules.analytics.service.AdminAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-wide analytics endpoint. Unlike the workspace-level
 * analytics this ignores the caller's memberships and aggregates
 * across every workspace.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin Analytics", description = "Platform-wide analytics dashboard data")
public class AdminAnalyticsController {

    private final AdminAnalyticsService adminAnalyticsService;

    public AdminAnalyticsController(AdminAnalyticsService adminAnalyticsService) {
        this.adminAnalyticsService = adminAnalyticsService;
    }

    @GetMapping
    @Operation(summary = "Get platform-wide analytics for the last N days (default 30)")
    public AdminAnalyticsResponse getAnalytics(
            @RequestParam(defaultValue = "30") int days) {
        return adminAnalyticsService.getPlatformAnalytics(days);
    }
}
