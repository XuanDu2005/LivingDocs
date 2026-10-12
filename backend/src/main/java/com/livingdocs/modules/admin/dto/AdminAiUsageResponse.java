package com.livingdocs.modules.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cross-tenant AI usage data for a single workspace. Returned by the
 * admin AI usage endpoint so operators can see a per-workspace breakdown
 * and set quotas from one screen.
 */
public record AdminAiUsageResponse(
        // Workspace identity
        String workspaceId,
        String workspaceName,
        String workspaceSlug,

        // Current period usage
        long todayTokens,
        long monthTokens,

        // Configured limits
        Long dailyLimit,
        Long monthlyLimit,
        Integer rateLimitPerMinute,

        // Aggregate summary (last 30 days)
        long last30DaysTokens,
        long last30DaysRequests,
        BigDecimal last30DaysCost,

        // Last activity
        String lastRequestAt
) {
}
