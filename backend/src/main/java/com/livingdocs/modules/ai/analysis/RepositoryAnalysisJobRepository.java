package com.livingdocs.modules.ai.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryAnalysisJobRepository extends JpaRepository<RepositoryAnalysisJob, UUID> {

    Optional<RepositoryAnalysisJob> findFirstByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

    List<RepositoryAnalysisJob> findTop20ByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    /**
     * The most recent job for a repository that has not finished yet.
     * Used to make a repeated click idempotent instead of running the
     * same analysis twice in parallel — two concurrent runs would race
     * on the same overview documents and burn the LLM budget twice.
     */
    @Query("""
            SELECT j FROM RepositoryAnalysisJob j
            WHERE j.repositoryId = :repositoryId
              AND j.status IN (com.livingdocs.modules.ai.analysis.RepositoryAnalysisJobStatus.PENDING,
                               com.livingdocs.modules.ai.analysis.RepositoryAnalysisJobStatus.RUNNING)
            ORDER BY j.createdAt DESC
            """)
    List<RepositoryAnalysisJob> findActiveForRepository(@Param("repositoryId") UUID repositoryId);
}
