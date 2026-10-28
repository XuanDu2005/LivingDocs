package com.livingdocs.modules.analytics.service;

import com.livingdocs.modules.ai.indexing.IndexJob;
import com.livingdocs.modules.ai.indexing.IndexJobRepository;
import com.livingdocs.modules.analytics.dto.AdminAnalyticsResponse;
import com.livingdocs.modules.analytics.dto.AdminAnalyticsResponse.DailyCount;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates platform-wide metrics for the admin analytics dashboard.
 *
 * <p>Unlike the workspace-level analytics this service deliberately
 * ignores the {@code workspaceId} filter — every count is global. Most
 * queries go through native SQL on the underlying database, which lets
 * us scale to large tables without loading rows into memory.
 *
 * <p>As with the workspace-level service, the native SQL is delegated to
 * {@link AnalyticsQueryExecutor} so a single failing statement cannot
 * poison the transaction that serves the rest of the dashboard.
 */
@Service
public class AdminAnalyticsService {

    private final AnalyticsQueries queries;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final DocumentRepository documentRepository;
    private final DriftAlertRepository driftAlertRepository;
    private final IndexJobRepository indexJobRepository;
    private final AuditLogRepository auditLogRepository;

    public AdminAnalyticsService(AnalyticsQueries queries,
                                  UserRepository userRepository,
                                  WorkspaceRepository workspaceRepository,
                                  DocumentRepository documentRepository,
                                  DriftAlertRepository driftAlertRepository,
                                  IndexJobRepository indexJobRepository,
                                  AuditLogRepository auditLogRepository) {
        this.queries = queries;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.driftAlertRepository = driftAlertRepository;
        this.indexJobRepository = indexJobRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public AdminAnalyticsResponse getPlatformAnalytics(int days) {
        days = Math.max(1, Math.min(365, days));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1);
        OffsetDateTime startDt = start.atStartOfDay().atOffset(ZoneOffset.UTC);

        long totalUsers = userRepository.count();
        long totalEnabledUsers = userRepository.findAllByEnabledTrue().size();
        long totalWorkspaces = workspaceRepository.count();
        long totalDocuments = documentRepository.count();
        long totalDriftAlerts = driftAlertRepository.count();
        long openDriftAlerts = safeCount(
                "SELECT COUNT(*) FROM drift_alerts WHERE resolution_status = 'OPEN'");
        long totalIndexJobs = indexJobRepository.count();
        long failedIndexJobs = safeCount(
                "SELECT COUNT(*) FROM indexing_jobs WHERE status = 'FAILED'");
        long totalAuditEvents = auditLogRepository.count();

        return new AdminAnalyticsResponse(
                days, start, end,
                totalUsers, totalEnabledUsers,
                totalWorkspaces, totalDocuments,
                totalDriftAlerts, openDriftAlerts,
                totalIndexJobs, failedIndexJobs, totalAuditEvents,
                getDocumentGrowth(start, end),
                getIndexingActivity(start),
                getActiveUsers(startDt, end),
                getDocumentStatusBreakdown(),
                getDriftSeverityBreakdown()
        );
    }

    private long safeCount(String sql) {
        return queries.queryCount(sql, Map.of());
    }

    private List<DailyCount> getDocumentGrowth(LocalDate start, LocalDate end) {
        String sql = "SELECT DATE(created_at) AS day, COUNT(*) AS cnt " +
                "FROM documents WHERE created_at >= :start " +
                "GROUP BY DATE(created_at) ORDER BY day";
        Map<String, Object> params = Map.of(
                "start", start.atStartOfDay().atOffset(ZoneOffset.UTC));
        return fillMissingDays(start, end, queries.queryCountsByDay(sql, params));
    }

    private List<DailyCount> getIndexingActivity(LocalDate start) {
        // Use a reasonable cap so we don't load millions of rows.
        List<IndexJob> jobs;
        try {
            jobs = indexJobRepository.findAll(org.springframework.data.domain.PageRequest
                    .of(0, 2000, org.springframework.data.domain.Sort.by("createdAt").descending())).getContent();
        } catch (Exception e) {
            return fillMissingDays(start, LocalDate.now(), new HashMap<>());
        }
        Map<LocalDate, Long> counts = new HashMap<>();
        LocalDate end = LocalDate.now();
        for (IndexJob j : jobs) {
            if (j.getCreatedAt() != null) {
                LocalDate day = j.getCreatedAt().toLocalDate();
                if (!day.isBefore(start) && !day.isAfter(end)) {
                    counts.merge(day, 1L, Long::sum);
                }
            }
        }
        return fillMissingDays(start, end, counts);
    }

    private List<DailyCount> getActiveUsers(OffsetDateTime start, LocalDate end) {
        String sql = "SELECT DATE(created_at) AS day, COUNT(DISTINCT actor_user_id) AS cnt " +
                "FROM audit_logs WHERE created_at >= :start " +
                "GROUP BY DATE(created_at) ORDER BY day";
        Map<String, Object> params = Map.of("start", start);
        return fillMissingDays(start.toLocalDate(), end,
                queries.queryCountsByDay(sql, params));
    }

    private Map<String, Integer> getDocumentStatusBreakdown() {
        String sql = "SELECT status, COUNT(*) FROM documents GROUP BY status";
        return queries.queryCountsByKey(sql, Map.of());
    }

    private Map<String, Integer> getDriftSeverityBreakdown() {
        String sql = "SELECT severity, COUNT(*) FROM drift_alerts " +
                "WHERE resolution_status = 'OPEN' GROUP BY severity";
        return queries.queryCountsByKey(sql, Map.of());
    }

    private List<DailyCount> fillMissingDays(LocalDate start, LocalDate end, Map<LocalDate, Long> counts) {
        List<DailyCount> result = new ArrayList<>();
        LocalDate d = start;
        while (!d.isAfter(end)) {
            result.add(new DailyCount(d, counts.getOrDefault(d, 0L)));
            d = d.plusDays(1);
        }
        return result;
    }
}
