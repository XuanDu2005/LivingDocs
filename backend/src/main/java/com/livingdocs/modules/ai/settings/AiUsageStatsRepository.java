package com.livingdocs.modules.ai.settings;

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
}
