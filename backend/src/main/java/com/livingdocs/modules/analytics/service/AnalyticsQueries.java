package com.livingdocs.modules.analytics.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The native SQL the analytics dashboards depend on.
 *
 * <p>Extracted as an interface so the services can be unit-tested
 * without a database, and so the "one broken table must not take the
 * whole dashboard down" behaviour is explicitly part of the contract.
 *
 * <p>Every method is specified to degrade to an empty result rather
 * than throw: the dashboards wrap each chart in its own section, so one
 * failing table should zero out one chart, not fail the page.
 */
public interface AnalyticsQueries {

    /**
     * Run a native query that returns grouped counts, keyed by a
     * {@code LocalDate} column.
     *
     * @return the counts keyed by day, empty when the query failed
     */
    Map<LocalDate, Long> queryCountsByDay(String sql, Map<String, Object> params);

    /**
     * Run a native query that returns one row per key and a count.
     *
     * @return a map of key to count, empty when the query failed
     */
    Map<String, Integer> queryCountsByKey(String sql, Map<String, Object> params);

    /**
     * Run a native query that returns a single scalar count.
     *
     * @return the count, zero when the query failed
     */
    long queryCount(String sql, Map<String, Object> params);

    /**
     * Run a native query that returns raw rows.
     *
     * @return the rows, empty when the query failed
     */
    List<Object[]> queryRows(String sql, Map<String, Object> params);
}
