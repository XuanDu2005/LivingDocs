package com.livingdocs.modules.analytics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide analytics snapshot used by the admin console.
 *
 * <p>Unlike {@link AnalyticsResponse} which is per-workspace, this
 * DTO aggregates across every workspace and includes counts of the
 * core entities (users, workspaces, documents, ...).
 */
public record AdminAnalyticsResponse(
    int days,
    LocalDate startDate,
    LocalDate endDate,
    long totalUsers,
    long totalEnabledUsers,
    long totalWorkspaces,
    long totalDocuments,
    long totalDriftAlerts,
    long openDriftAlerts,
    long totalIndexJobs,
    long failedIndexJobs,
    long totalAuditEvents,
    List<DailyCount> documentGrowth,
    List<DailyCount> indexingActivity,
    List<DailyCount> activeUsers,
    Map<String, Integer> documentStatusBreakdown,
    Map<String, Integer> driftSeverityBreakdown
) {
    public record DailyCount(LocalDate date, long count) {}
}
