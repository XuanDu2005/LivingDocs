package com.livingdocs.modules.ai.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BulkGenerationJobRepository extends JpaRepository<BulkGenerationJob, UUID> {

    Optional<BulkGenerationJob> findFirstByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

    List<BulkGenerationJob> findTop20ByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    /**
     * The most recent job for a repository that has not finished yet.
     * Used to make a repeated click idempotent instead of running the
     * same bulk generation twice in parallel.
     */
    @Query("""
            SELECT j FROM BulkGenerationJob j
            WHERE j.repositoryId = :repositoryId
              AND j.status IN (com.livingdocs.modules.ai.bulk.BulkGenerationJobStatus.PENDING,
                               com.livingdocs.modules.ai.bulk.BulkGenerationJobStatus.RUNNING)
            ORDER BY j.createdAt DESC
            """)
    List<BulkGenerationJob> findActiveForRepository(@Param("repositoryId") UUID repositoryId);
}
