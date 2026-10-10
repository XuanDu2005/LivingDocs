package com.livingdocs.modules.analytics.service;

import com.livingdocs.modules.analytics.dto.AnalyticsResponse;
import com.livingdocs.modules.analytics.dto.AnalyticsResponse.DailyCount;
import com.livingdocs.modules.ai.indexing.IndexJob;
import com.livingdocs.modules.ai.indexing.IndexJobRepository;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
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
import java.util.UUID;

/**
 * Aggregates analytics for a workspace over a time period.
 */
@Service
public class AnalyticsService {

    private final EntityManager entityManager;
    private final AuditLogRepository auditLogRepository;
    private final IndexJobRepository indexJobRepository;

    public AnalyticsService(EntityManager entityManager,
                             AuditLogRepository auditLogRepository,
                             IndexJobRepository indexJobRepository) {
        this.entityManager = entityManager;
        this.auditLogRepository = auditLogRepository;
        this.indexJobRepository = indexJobRepository;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(UUID workspaceId, int days) {
        days = Math.max(1, Math.min(365, days));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1);
        OffsetDateTime startDt = start.atStartOfDay().atOffset(ZoneOffset.UTC);

        return new AnalyticsResponse(
                days,
                start,
                end,
                getDocumentGrowth(workspaceId, start, end),
                getActiveUsers(workspaceId, startDt),
                getDriftTrends(workspaceId, start, end),
                getIndexingActivity(workspaceId, start, end),
                getDocumentStatusBreakdown(workspaceId),
                getDriftSeverityBreakdown(workspaceId)
        );
    }

    private List<DailyCount> getDocumentGrowth(UUID workspaceId, LocalDate start, LocalDate end) {
        // Daily document creation counts
        String sql = "SELECT DATE(created_at) AS day, COUNT(*) AS cnt " +
                "FROM documents WHERE workspace_id = :ws " +
                "AND created_at >= :start GROUP BY DATE(created_at) ORDER BY day";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("ws", workspaceId);
        q.setParameter("start", start.atStartOfDay().atOffset(ZoneOffset.UTC));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        Map<LocalDate, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((java.sql.Date) row[0]).toLocalDate(), ((Number) row[1]).longValue());
        }
        return fillMissingDays(start, end, counts);
    }

    private List<DailyCount> getActiveUsers(UUID workspaceId, OffsetDateTime start) {
        // Distinct users per day from audit logs
        String sql = "SELECT DATE(created_at) AS day, COUNT(DISTINCT actor_user_id) AS cnt " +
                "FROM audit_logs WHERE workspace_id = :ws " +
                "AND created_at >= :start GROUP BY DATE(created_at) ORDER BY day";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("ws", workspaceId);
        q.setParameter("start", start);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        Map<LocalDate, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((java.sql.Date) row[0]).toLocalDate(), ((Number) row[1]).longValue());
        }
        LocalDate end = LocalDate.now();
        return fillMissingDays(start.toLocalDate(), end, counts);
    }

    private List<DailyCount> getDriftTrends(UUID workspaceId, LocalDate start, LocalDate end) {
        String sql = "SELECT DATE(created_at) AS day, COUNT(*) AS cnt " +
                "FROM drift_alerts WHERE workspace_id = :ws " +
                "AND created_at >= :start GROUP BY DATE(created_at) ORDER BY day";
        try {
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("ws", workspaceId);
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

    private List<DailyCount> getIndexingActivity(UUID workspaceId, LocalDate start, LocalDate end) {
        List<IndexJob> jobs = indexJobRepository.findTop50ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        Map<LocalDate, Long> counts = new HashMap<>();
        for (IndexJob j : jobs) {
            if (j.getCreatedAt() != null) {
                LocalDate day = j.getCreatedAt().toLocalDate();
                if (!day.isBefore(start)) {
                    counts.merge(day, 1L, Long::sum);
                }
            }
        }
        return fillMissingDays(start, end, counts);
    }

    private Map<String, Integer> getDocumentStatusBreakdown(UUID workspaceId) {
        String sql = "SELECT status, COUNT(*) FROM documents WHERE workspace_id = :ws GROUP BY status";
        try {
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("ws", workspaceId);
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

    private Map<String, Integer> getDriftSeverityBreakdown(UUID workspaceId) {
        String sql = "SELECT severity, COUNT(*) FROM drift_alerts " +
                "WHERE workspace_id = :ws AND status = 'OPEN' GROUP BY severity";
        try {
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("ws", workspaceId);
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
