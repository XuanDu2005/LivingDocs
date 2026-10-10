package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.AdminAiUsageResponse;
import com.livingdocs.modules.admin.dto.DailyUsageRow;
import com.livingdocs.modules.ai.settings.AiUsageStats;
import com.livingdocs.modules.ai.settings.AiUsageStatsRepository;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-tenant AI usage administration.
 *
 * <p>Provides the platform-wide AI usage dashboard: a 30-day token
 * consumption chart aggregated across all workspaces, plus a per-workspace
 * breakdown table so admins can identify heavy consumers, set quotas,
 * or audit spend.
 */
@Service
public class AdminAiUsageService {

    private static final int CHART_DAYS = 30;

    private final AiUsageStatsRepository usageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceSettingsRepository settingsRepository;

    public AdminAiUsageService(AiUsageStatsRepository usageRepository,
                               WorkspaceRepository workspaceRepository,
                               WorkspaceSettingsRepository settingsRepository) {
        this.usageRepository = usageRepository;
        this.workspaceRepository = workspaceRepository;
        this.settingsRepository = settingsRepository;
    }

    /**
     * Platform-wide 30-day daily token chart. Returns one row per day,
     * summed across all workspaces. Days with no usage are filled in
     * with zeros so the chart renders as a continuous series.
     */
    @Transactional(readOnly = true)
    public List<DailyUsageRow> platformDailyChart() {
        LocalDate start = LocalDate.now().minusDays(CHART_DAYS - 1);
        Pageable page = PageRequest.of(0, CHART_DAYS * 3);

        List<AiUsageStats> rows = usageRepository.findAllSince(start, page);

        // Aggregate by date across all workspaces.
        Map<LocalDate, Long> tokensByDate = new HashMap<>();
        Map<LocalDate, Integer> requestsByDate = new HashMap<>();
        Map<LocalDate, BigDecimal> costByDate = new HashMap<>();
        for (AiUsageStats s : rows) {
            tokensByDate.merge(s.getDate(), s.getTotalTokens(), Long::sum);
            requestsByDate.merge(s.getDate(), s.getRequestCount(), Integer::sum);
            costByDate.merge(s.getDate(), s.getEstimatedCost(), BigDecimal::add);
        }

        List<DailyUsageRow> result = new ArrayList<>();
        LocalDate cursor = start;
        LocalDate today = LocalDate.now();
        while (!cursor.isAfter(today)) {
            result.add(new DailyUsageRow(
                    cursor,
                    tokensByDate.getOrDefault(cursor, 0L),
                    requestsByDate.getOrDefault(cursor, 0),
                    costByDate.getOrDefault(cursor, BigDecimal.ZERO)
            ));
            cursor = cursor.plusDays(1);
        }
        return result;
    }

    /**
     * Platform-wide summary numbers for the stat cards.
     */
    @Transactional(readOnly = true)
    public PlatformSummary platformSummary() {
        LocalDate today = LocalDate.now();
        LocalDate start30 = today.minusDays(CHART_DAYS - 1);

        BigDecimal cost30 = usageRepository.sumCostSince(start30);

        List<AiUsageStats> rows30 = usageRepository.findAllSince(start30,
                PageRequest.of(0, CHART_DAYS * 3));
        long totalTokens30 = 0;
        long totalRequests30 = 0;
        for (AiUsageStats s : rows30) {
            totalTokens30 += s.getTotalTokens();
            totalRequests30 += s.getRequestCount();
        }

        long todayTokens = rows30.stream()
                .filter(s -> s.getDate().isEqual(today))
                .mapToLong(AiUsageStats::getTotalTokens)
                .sum();

        int wsCount = (int) workspaceRepository.count();
        return new PlatformSummary(
                wsCount,
                totalTokens30,
                totalRequests30,
                cost30 != null ? cost30 : BigDecimal.ZERO,
                todayTokens
        );
    }

    /**
     * Per-workspace usage breakdown. Returns one row per workspace that
     * has ever used AI (or all workspaces if no limit is configured),
     * sorted by total tokens descending so heavy consumers appear first.
     */
    @Transactional(readOnly = true)
    public List<AdminAiUsageResponse> workspaceBreakdown() {
        LocalDate today = LocalDate.now();
        LocalDate start30 = today.minusDays(CHART_DAYS - 1);
        Pageable page = PageRequest.of(0, 1000);

        // Map: workspaceId -> aggregated stats
        Map<UUID, StatsAccumulator> byWs = new HashMap<>();
        for (AiUsageStats s : usageRepository.findAllSince(start30, page)) {
            byWs.computeIfAbsent(s.getWorkspaceId(), k -> new StatsAccumulator())
                    .add(s.getTotalTokens(), s.getRequestCount(), s.getEstimatedCost());
        }

        // Pre-load all settings in one shot.
        Map<UUID, WorkspaceSettings> settingsByWs = new HashMap<>();
        for (WorkspaceSettings ws : settingsRepository.findAll()) {
            settingsByWs.put(ws.getWorkspaceId(), ws);
        }

        List<Workspace> workspaces = workspaceRepository.findAll();
        List<AdminAiUsageResponse> result = new ArrayList<>();

        for (Workspace ws : workspaces) {
            StatsAccumulator acc = byWs.getOrDefault(ws.getId(), StatsAccumulator.ZERO);
            WorkspaceSettings settings = settingsByWs.get(ws.getId());

            // Fetch today's tokens and month-to-date
            long todayTokens = usageRepository.sumTokensOnDate(ws.getId(), today);
            long monthTokens = usageRepository.sumTokensSince(ws.getId(), today.withDayOfMonth(1));

            result.add(new AdminAiUsageResponse(
                    ws.getId().toString(),
                    ws.getName(),
                    ws.getSlug(),
                    todayTokens,
                    monthTokens,
                    settings != null ? settings.getDailyTokenLimit() : null,
                    settings != null ? settings.getMonthlyTokenLimit() : null,
                    settings != null ? settings.getRateLimitPerMin() : null,
                    acc.totalTokens,
                    acc.totalRequests,
                    acc.totalCost,
                    null
            ));
        }

        // Sort by 30-day tokens descending
        result.sort((a, b) -> Long.compare(b.last30DaysTokens(), a.last30DaysTokens()));
        return result;
    }

    /**
     * Daily usage chart for a single workspace (used by the detail panel).
     */
    @Transactional(readOnly = true)
    public List<DailyUsageRow> workspaceDailyChart(UUID workspaceId) {
        LocalDate start = LocalDate.now().minusDays(CHART_DAYS - 1);
        List<AiUsageStats> rows = usageRepository
                .findTopByWorkspaceIdOrderByDateDesc(workspaceId, PageRequest.of(0, CHART_DAYS));

        Map<LocalDate, AiUsageStats> byDate = new HashMap<>();
        for (AiUsageStats s : rows) byDate.put(s.getDate(), s);

        List<DailyUsageRow> result = new ArrayList<>();
        LocalDate cursor = start;
        LocalDate today = LocalDate.now();
        while (!cursor.isAfter(today)) {
            AiUsageStats s = byDate.get(cursor);
            result.add(new DailyUsageRow(
                    cursor,
                    s != null ? s.getTotalTokens() : 0L,
                    s != null ? s.getRequestCount() : 0,
                    s != null ? s.getEstimatedCost() : BigDecimal.ZERO
            ));
            cursor = cursor.plusDays(1);
        }
        return result;
    }

    /**
     * Update token limits for a workspace from the admin screen.
     */
    @Transactional
    public AdminAiUsageResponse updateLimits(UUID workspaceId,
                                            Long dailyLimit,
                                            Long monthlyLimit,
                                            Integer rateLimitPerMinute) {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new NotFoundException("Workspace not found");
        }

        WorkspaceSettings settings = settingsRepository.findById(workspaceId)
                .orElseGet(() -> {
                    WorkspaceSettings ns = new WorkspaceSettings(workspaceId);
                    return settingsRepository.save(ns);
                });
        settings.setDailyTokenLimit(dailyLimit);
        settings.setMonthlyTokenLimit(monthlyLimit);
        settings.setRateLimitPerMin(rateLimitPerMinute);
        settingsRepository.save(settings);

        // Re-read aggregated data for the response.
        LocalDate today = LocalDate.now();
        LocalDate start30 = today.minusDays(CHART_DAYS - 1);
        long todayTokens = usageRepository.sumTokensOnDate(workspaceId, today);
        long monthTokens = usageRepository.sumTokensSince(workspaceId, today.withDayOfMonth(1));

        List<AiUsageStats> rows30 = usageRepository.findAllSince(start30,
                PageRequest.of(0, CHART_DAYS * 3));
        long tokens30 = 0;
        long req30 = 0;
        BigDecimal cost30 = BigDecimal.ZERO;
        for (AiUsageStats s : rows30) {
            if (s.getWorkspaceId().equals(workspaceId)) {
                tokens30 += s.getTotalTokens();
                req30 += s.getRequestCount();
                cost30 = cost30.add(s.getEstimatedCost());
            }
        }

        Workspace ws = workspaceRepository.findById(workspaceId).orElseThrow();
        return new AdminAiUsageResponse(
                ws.getId().toString(),
                ws.getName(),
                ws.getSlug(),
                todayTokens,
                monthTokens,
                dailyLimit,
                monthlyLimit,
                rateLimitPerMinute,
                tokens30,
                req30,
                cost30,
                null
        );
    }

    // ---- helpers ----

    private static class StatsAccumulator {
        long totalTokens = 0;
        long totalRequests = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        static final StatsAccumulator ZERO = new StatsAccumulator();
        void add(long tokens, int requests, BigDecimal cost) {
            totalTokens += tokens;
            totalRequests += requests;
            totalCost = totalCost.add(cost != null ? cost : BigDecimal.ZERO);
        }
    }

    public record PlatformSummary(
            int workspaceCount,
            long last30DaysTokens,
            long last30DaysRequests,
            BigDecimal last30DaysCost,
            long todayTokens
    ) {}
}
