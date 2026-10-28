package com.livingdocs.modules.analytics;

import com.livingdocs.modules.ai.indexing.IndexJobRepository;
import com.livingdocs.modules.analytics.dto.AnalyticsResponse;
import com.livingdocs.modules.analytics.service.AnalyticsQueries;
import com.livingdocs.modules.analytics.service.AnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the workspace analytics dashboard.
 *
 * <h2>What went wrong</h2>
 * The drift-severity breakdown queried {@code drift_alerts.status}, but
 * that table stores the workflow state in {@code resolution_status}. The
 * statement failed, the {@code catch} block swallowed the error and
 * returned an empty map — but PostgreSQL had already aborted the
 * surrounding transaction, so Hibernate marked it rollback-only and the
 * outer transaction threw
 * {@link org.springframework.transaction.UnexpectedRollbackException}.
 * The user saw a 500 on the whole dashboard instead of a chart with
 * zeroes.
 *
 * <p>These tests pin both halves of the fix: the query names the right
 * column, and a failing query degrades one section without taking the
 * rest of the response down with it.
 */
class AnalyticsServiceTest {

    private IndexJobRepository indexJobRepository;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        indexJobRepository = mock(IndexJobRepository.class);
        when(indexJobRepository.findTop50ByWorkspaceIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of());
        workspaceId = UUID.randomUUID();
    }

    @Test
    void driftSeverityBreakdownFiltersOnResolutionStatusNotStatus() {
        RecordingQueries queries = new RecordingQueries();
        serviceWith(queries).getAnalytics(workspaceId, 30);

        String driftSql = queries.sqlFor("resolution_status");
        assertNotNull(driftSql, "the drift breakdown must filter on resolution_status");
        assertTrue(driftSql.contains("resolution_status = 'OPEN'"),
                "the open-drift filter must use resolution_status, got: " + driftSql);
        assertTrue(!driftSql.contains("AND status = 'OPEN'"),
                "the query must not reference the non-existent `status` column, got: " + driftSql);
    }

    @Test
    void aFailingQueryDoesNotFailTheWholeResponse() {
        // Every statement fails, the way it would if the drift table had
        // an unexpected shape. The dashboard must still answer with the
        // days it knows about.
        RecordingQueries failing = new RecordingQueries() {
            @Override
            public Map<String, Integer> queryCountsByKey(String sql, Map<String, Object> params) {
                return Map.of();
            }

            @Override
            public Map<LocalDate, Long> queryCountsByDay(String sql, Map<String, Object> params) {
                return Map.of();
            }
        };
        serviceWith(failing);

        AnalyticsResponse response = serviceWith(failing).getAnalytics(workspaceId, 7);

        assertNotNull(response);
        assertEquals(7, response.days());
        assertEquals(7, response.documentGrowth().size(),
                "the daily series must still cover every day of the window");
        assertEquals(7, response.driftTrends().size());
        assertTrue(response.driftSeverityBreakdown().isEmpty());
    }

    @Test
    void theDailySeriesCoversEveryDayInTheWindow() {
        AnalyticsResponse response = serviceWith(new RecordingQueries())
                .getAnalytics(workspaceId, 30);

        assertEquals(30, response.documentGrowth().size());
        assertEquals(30, response.activeUsers().size());
        assertEquals(30, response.driftTrends().size());
        assertEquals(30, response.indexingActivity().size());
        for (AnalyticsResponse.DailyCount c : response.documentGrowth()) {
            assertNotNull(c.date());
            assertEquals(0L, c.count());
        }
    }

    @Test
    void theWindowIsClampedToASaneRange() {
        AnalyticsService service = serviceWith(new RecordingQueries());

        assertEquals(1, service.getAnalytics(workspaceId, 0).days());
        assertEquals(365, service.getAnalytics(workspaceId, 10_000).days());
    }

    @Test
    void countsFromTheDatabaseReachTheResponse() {
        RecordingQueries queries = new RecordingQueries();
        queries.driftCounts = Map.of("HIGH", 2, "LOW", 1);
        queries.docsByStatus = Map.of("PUBLISHED", 5);

        AnalyticsResponse response = serviceWith(queries).getAnalytics(workspaceId, 30);

        assertEquals(2, response.driftSeverityBreakdown().get("HIGH"));
        assertEquals(1, response.driftSeverityBreakdown().get("LOW"));
        assertEquals(5, response.documentStatusBreakdown().get("PUBLISHED"));
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private AnalyticsService serviceWith(AnalyticsQueries queries) {
        return new AnalyticsService(queries, indexJobRepository);
    }

    /** Records the SQL it was asked to run so a test can assert on it. */
    static class RecordingQueries implements AnalyticsQueries {
        private final List<String> statements = new ArrayList<>();
        Map<String, Integer> driftCounts = Map.of();
        Map<String, Integer> docsByStatus = Map.of();

        @Override
        public Map<LocalDate, Long> queryCountsByDay(String sql, Map<String, Object> params) {
            statements.add(sql);
            return Map.of();
        }

        @Override
        public Map<String, Integer> queryCountsByKey(String sql, Map<String, Object> params) {
            statements.add(sql);
            if (sql.contains("drift_alerts")) return driftCounts;
            if (sql.contains("documents")) return docsByStatus;
            return Map.of();
        }

        @Override
        public long queryCount(String sql, Map<String, Object> params) {
            statements.add(sql);
            return 0L;
        }

        @Override
        public List<Object[]> queryRows(String sql, Map<String, Object> params) {
            statements.add(sql);
            return List.of();
        }

        String sqlFor(String fragment) {
            return statements.stream()
                    .filter(s -> s.contains(fragment))
                    .findFirst()
                    .orElse(null);
        }
    }
}
