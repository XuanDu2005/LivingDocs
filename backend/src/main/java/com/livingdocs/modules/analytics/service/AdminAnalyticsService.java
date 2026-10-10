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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
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
 * queries go through the entity manager to leverage native SQL on the
 * underlying database, which lets us scale to large tables without
 * loading rows into memory.
 */
@Service
public class AdminAnalyticsService {

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final DocumentRepository documentRepository;
    private final DriftAlertRepository driftAlertRepository;
    private final IndexJobRepository indexJobRepository;
    private final AuditLogRepository auditLogRepository;

    public AdminAnalyticsService(UserRepository userRepository,
                                  WorkspaceRepository workspaceRepository,
                                  DocumentRepository documentRepository,
                                  DriftAlertRepository driftAlertRepository,
                                  IndexJobRepository indexJobRepository,
                                  AuditLogRepository auditLogRepository) {
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
        try {
            Query q = entityManager.createNativeQuery(sql);
            Object r = q.getSingleResult();
            return r == null ? 0L : ((Number) r).longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<DailyCount> getDocumentGrowth(LocalDate start, LocalDate end) {
        String sql = "SELECT DATE(created_at) AS day, COUNT(*) AS cnt " +
                "FROM documents WHERE created_at >= :start " +
                "GROUP BY DATE(created_at) ORDER BY day";
        try {
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("start", start.atStartOfDay().atOffset(ZoneOffset.UTC));
            @SuppressWarnings("unchecked")
            List<Object[]> rows = q.getResultList();
            Map<LocalDate, Long> counts = new HashMap<>();
            for (Object[] row : rows) {
                counts.put(((java.sql.Date) row[0]).toLocalDate(), ((Number) row[1]).longValue());
            }
            return fillMissingDays(start, end, counts);
        } catch (Exception e) {
            return fillMissingDays(start, end, new HashMap<>());
        }
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
        try {
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("start", start);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = q.getResultList();
            Map<LocalDate, Long> counts = new HashMap<>();
            for (Object[] row : rows) {
                counts.put(((java.sql.Date) row[0]).toLocalDate(), ((Number) row[1]).longValue());
            }
            return fillMissingDays(start.toLocalDate(), end, counts);
        } catch (Exception e) {
            return fillMissingDays(start.toLocalDate(), end, new HashMap<>());
        }
    }

    private Map<String, Integer> getDocumentStatusBreakdown() {
        String sql = "SELECT status, COUNT(*) FROM documents GROUP BY status";
        try {
            Query q = entityManager.createNativeQuery(sql);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = q.getResultList();
            Map<String, Integer> map = new HashMap<>();
            for (Object[] row : rows) {
                map.put((String) row[0], ((Number) row[1]).intValue());
            }
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private Map<String, Integer> getDriftSeverityBreakdown() {
        String sql = "SELECT severity, COUNT(*) FROM drift_alerts " +
                "WHERE resolution_status = 'OPEN' GROUP BY severity";
        try {
            Query q = entityManager.createNativeQuery(sql);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = q.getResultList();
            Map<String, Integer> map = new HashMap<>();
            for (Object[] row : rows) {
                map.put((String) row[0], ((Number) row[1]).intValue());
            }
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
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
