package com.livingdocs.modules.ai.settings;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiUsageStatsRepository extends JpaRepository<AiUsageStats, UUID> {

    Optional<AiUsageStats> findByWorkspaceIdAndDate(UUID workspaceId, LocalDate date);

    @Query("SELECT s FROM AiUsageStats s WHERE s.workspaceId = :workspaceId ORDER BY s.date DESC")
    List<AiUsageStats> findRecentByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    @Query("SELECT COALESCE(SUM(s.totalTokens), 0) FROM AiUsageStats s " +
           "WHERE s.workspaceId = :workspaceId AND s.date >= :startDate")
    long sumTokensSince(@Param("workspaceId") UUID workspaceId, @Param("startDate") LocalDate startDate);

    @Query("SELECT COALESCE(SUM(s.totalTokens), 0) FROM AiUsageStats s " +
           "WHERE s.workspaceId = :workspaceId AND s.date = :date")
    long sumTokensOnDate(@Param("workspaceId") UUID workspaceId, @Param("date") LocalDate date);

    /**
     * Return the last N daily usage rows for a workspace, newest first.
     * Used by the admin graph.
     */
    @Query("SELECT s FROM AiUsageStats s WHERE s.workspaceId = :workspaceId ORDER BY s.date DESC")
    List<AiUsageStats> findTopByWorkspaceIdOrderByDateDesc(@Param("workspaceId") UUID workspaceId, org.springframework.data.domain.Pageable pageable);

    /**
     * Return every row in the last N days across all workspaces.
     * Used by the platform-wide usage graph.
     */
    @Query("SELECT s FROM AiUsageStats s WHERE s.date >= :startDate ORDER BY s.date ASC")
    List<AiUsageStats> findAllSince(@Param("startDate") LocalDate startDate, Pageable pageable);

    /**
     * Sum cost across all workspaces for the last N days.
     */
    @Query("SELECT COALESCE(SUM(s.estimatedCost), 0) FROM AiUsageStats s WHERE s.date >= :startDate")
    java.math.BigDecimal sumCostSince(@Param("startDate") LocalDate startDate);
}
