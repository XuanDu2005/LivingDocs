package com.livingdocs.modules.analytics.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the dashboards' native SQL in an isolated transaction.
 *
 * <h2>Why this exists</h2>
 * The analytics queries are individually wrapped so that a single
 * broken table degrades one chart to zeroes instead of failing the
 * whole page. That only works if the failed statement does not poison
 * the surrounding transaction — and it does. PostgreSQL aborts the
 * whole transaction on the first error, so Hibernate marks it
 * {@code rollback-only} and the {@code catch} block's "graceful" return
 * is discarded. The outer {@code @Transactional} then throws
 * {@link org.springframework.transaction.UnexpectedRollbackException} at
 * commit time, turning a partial outage into an HTTP 500.
 *
 * <p>Running each query with {@link Propagation#REQUIRES_NEW} gives it
 * its own transaction: a failure rolls back only that statement's
 * transaction, and the caller's transaction stays usable.
 *
 * <p>These are all read-only aggregations, so an isolated read
 * transaction is also the semantically correct choice — each chart
 * should reflect a consistent snapshot on its own, not be entangled
 * with the others.
 */
@Component
public class AnalyticsQueryExecutor implements AnalyticsQueries {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsQueryExecutor.class);

    private final EntityManager entityManager;

    public AnalyticsQueryExecutor(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Object[]> queryRows(String sql, Map<String, Object> params) {
        try {
            Query q = entityManager.createNativeQuery(sql);
            params.forEach(q::setParameter);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = q.getResultList();
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("Analytics query failed, degrading to an empty result: {} — {}",
                    e.getMessage(), sql);
            return List.of();
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Map<String, Integer> queryCountsByKey(String sql, Map<String, Object> params) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : queryRows(sql, params)) {
            if (row == null || row.length < 2) continue;
            Object key = row[0];
            if (key == null) continue;
            map.put(String.valueOf(key), ((Number) row[1]).intValue());
        }
        return map;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Map<LocalDate, Long> queryCountsByDay(String sql, Map<String, Object> params) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (Object[] row : queryRows(sql, params)) {
            if (row == null || row.length < 2) continue;
            Object day = row[0];
            if (!(day instanceof java.sql.Date sqlDate)) continue;
            counts.merge(sqlDate.toLocalDate(), ((Number) row[1]).longValue(), Long::sum);
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long queryCount(String sql, Map<String, Object> params) {
        try {
            Query q = entityManager.createNativeQuery(sql);
            params.forEach(q::setParameter);
            Object r = q.getSingleResult();
            return r == null ? 0L : ((Number) r).longValue();
        } catch (Exception e) {
            log.warn("Analytics count query failed, degrading to zero: {} — {}",
                    e.getMessage(), sql);
            return 0L;
        }
    }
}
