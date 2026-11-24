package com.livingdocs.modules.docsync.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocSyncJobRepository extends JpaRepository<DocSyncJob, UUID> {

    List<DocSyncJob> findTop20ByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    /**
     * The most recent job for a repository that has not finished yet.
     *
     * <p>Used to make a repeated click idempotent instead of running the
     * same sync twice in parallel — two concurrent runs would create
     * duplicate proposals and burn the LLM budget twice.
     */
    @Query("""
            SELECT j FROM DocSyncJob j
            WHERE j.repositoryId = :repositoryId
              AND j.status IN (com.livingdocs.modules.docsync.job.DocSyncJobStatus.PENDING,
                               com.livingdocs.modules.docsync.job.DocSyncJobStatus.RUNNING)
            ORDER BY j.createdAt DESC
            """)
    List<DocSyncJob> findActiveForRepository(@Param("repositoryId") UUID repositoryId);

    /**
     * The jobs that were left running by a previous instance of the
     * service.
     *
     * <p>A sync runs on a background thread. When the process is
     * stopped — a restart, a crash, a redeploy — that thread dies with
     * the job still marked {@code RUNNING}, and no worker will ever
     * come back to finish it. Such a job would block every later sync
     * of the same repository, so it is found by age and closed.
     */
    @Query("""
            SELECT j FROM DocSyncJob j
            WHERE j.status IN (com.livingdocs.modules.docsync.job.DocSyncJobStatus.PENDING,
                               com.livingdocs.modules.docsync.job.DocSyncJobStatus.RUNNING)
              AND j.createdAt < :cutoff
            """)
    List<DocSyncJob> findStaleBefore(@Param("cutoff") java.time.OffsetDateTime cutoff);
}
