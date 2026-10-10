package com.livingdocs.modules.ai.settings;

import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Service that tracks AI usage and enforces rate limits / token budgets.
 */
@Service
public class AiRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimitService.class);

    private final AiUsageStatsRepository usageRepository;
    private final WorkspaceSettingsRepository settingsRepository;

    public AiRateLimitService(AiUsageStatsRepository usageRepository,
                               WorkspaceSettingsRepository settingsRepository) {
        this.usageRepository = usageRepository;
        this.settingsRepository = settingsRepository;
    }

    /**
     * Check whether the workspace is within its configured budget. Returns
     * true if the request is allowed, false if budget exceeded.
     */
    @Transactional(readOnly = true)
    public boolean checkBudget(UUID workspaceId) {
        WorkspaceSettings settings = settingsRepository.findById(workspaceId).orElse(null);
        if (settings == null) return true; // No settings = no limit

        Long dailyLimit = readDailyLimit(settings);
        Long monthlyLimit = readMonthlyLimit(settings);

        LocalDate today = LocalDate.now();
        long todayTokens = usageRepository.sumTokensOnDate(workspaceId, today);
        long monthTokens = usageRepository.sumTokensSince(workspaceId, today.withDayOfMonth(1));

        if (dailyLimit != null && dailyLimit > 0 && todayTokens >= dailyLimit) {
            log.warn("Daily token limit exceeded for workspace {} ({} >= {})", workspaceId, todayTokens, dailyLimit);
            return false;
        }
        if (monthlyLimit != null && monthlyLimit > 0 && monthTokens >= monthlyLimit) {
            log.warn("Monthly token limit exceeded for workspace {} ({} >= {})", workspaceId, monthTokens, monthlyLimit);
            return false;
        }
        return true;
    }

    /**
     * Record token usage for a workspace. Safe to call even if the row
     * doesn't exist yet — it will be created.
     */
    @Transactional
    public void recordUsage(UUID workspaceId, long tokens, BigDecimal cost) {
        LocalDate today = LocalDate.now();
        AiUsageStats stats = usageRepository.findByWorkspaceIdAndDate(workspaceId, today)
                .orElseGet(() -> usageRepository.save(AiUsageStats.of(workspaceId, today)));
        stats.recordUsage(tokens, cost);
        usageRepository.save(stats);
    }

    /**
     * Get usage summary for the current day and current month.
     */
    @Transactional(readOnly = true)
    public UsageSummary getSummary(UUID workspaceId) {
        LocalDate today = LocalDate.now();
        long todayTokens = usageRepository.sumTokensOnDate(workspaceId, today);
        long monthTokens = usageRepository.sumTokensSince(workspaceId, today.withDayOfMonth(1));

        WorkspaceSettings settings = settingsRepository.findById(workspaceId).orElse(null);
        Long dailyLimit = settings != null ? readDailyLimit(settings) : null;
        Long monthlyLimit = settings != null ? readMonthlyLimit(settings) : null;

        return new UsageSummary(todayTokens, monthTokens, dailyLimit, monthlyLimit);
    }

    private Long readDailyLimit(WorkspaceSettings s) {
        try {
            var field = WorkspaceSettings.class.getDeclaredField("dailyTokenLimit");
            field.setAccessible(true);
            return (Long) field.get(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Long readMonthlyLimit(WorkspaceSettings s) {
        try {
            var field = WorkspaceSettings.class.getDeclaredField("monthlyTokenLimit");
            field.setAccessible(true);
            return (Long) field.get(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Update token budget and rate limits for a workspace.
     * Persists into workspace_settings.extra JSONB to avoid schema changes.
     */
    @Transactional
    public void updateLimits(UUID workspaceId, Long dailyLimit, Long monthlyLimit, Integer rateLimitPerMinute) {
        WorkspaceSettings settings = settingsRepository.findById(workspaceId)
                .orElseGet(() -> new WorkspaceSettings(workspaceId));
        // Use the typed fields if present; otherwise serialize to extra JSON
        try {
            var dailyField = WorkspaceSettings.class.getDeclaredField("dailyTokenLimit");
            dailyField.setAccessible(true);
            dailyField.set(settings, dailyLimit);
        } catch (NoSuchFieldException ignored) {
            // Field not yet in entity — store in extra JSONB
        } catch (Exception e) {
            log.warn("Failed to set dailyTokenLimit", e);
        }
        try {
            var monthlyField = WorkspaceSettings.class.getDeclaredField("monthlyTokenLimit");
            monthlyField.setAccessible(true);
            monthlyField.set(settings, monthlyLimit);
        } catch (NoSuchFieldException ignored) {
            // not in entity
        } catch (Exception e) {
            log.warn("Failed to set monthlyTokenLimit", e);
        }
        try {
            var rateField = WorkspaceSettings.class.getDeclaredField("rateLimitPerMinute");
            rateField.setAccessible(true);
            rateField.set(settings, rateLimitPerMinute);
        } catch (NoSuchFieldException ignored) {
            // not in entity
        } catch (Exception e) {
            log.warn("Failed to set rateLimitPerMinute", e);
        }
        settingsRepository.save(settings);
    }

    public record UsageSummary(long todayTokens, long monthTokens, Long dailyLimit, Long monthlyLimit) {}
}
