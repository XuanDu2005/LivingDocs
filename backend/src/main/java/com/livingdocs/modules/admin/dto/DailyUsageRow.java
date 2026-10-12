package com.livingdocs.modules.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One data point in the daily token-usage time series. Used by the
 * admin AI usage graph (30-day bar chart) and by the per-workspace
 * detail graph.
 */
public record DailyUsageRow(
        LocalDate date,
        long tokens,
        int requests,
        BigDecimal cost
) {
}
