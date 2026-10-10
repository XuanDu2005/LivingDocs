package com.livingdocs.modules.analytics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Aggregated analytics for a workspace over a time period.
 */
public record AnalyticsResponse(
    int days,
    LocalDate startDate,
    LocalDate endDate,
    List<DailyCount> documentGrowth,
    List<DailyCount> activeUsers,
    List<DailyCount> driftTrends,
    List<DailyCount> indexingActivity,
    Map<String, Integer> documentStatusBreakdown,
    Map<String, Integer> driftSeverityBreakdown
) {
    public record DailyCount(LocalDate date, long count) {}
}
