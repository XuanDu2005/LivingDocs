package com.livingdocs.modules.ai.indexing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link IndexJob}.
 *
 * <p>The repository surface is intentionally small: enqueue
 * (handled by the service via {@code save}), status polling
 * (findById, findByWorkspace), and the "is a job already running"
 * guard used by the controller.
 */
@Repository
public interface IndexJobRepository extends JpaRepository<IndexJob, UUID> {

    /**
     * Return all jobs for a workspace, newest first. Used by the
     * "indexing jobs" admin/dashboard listing.
     */
    Page<IndexJob> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable pageable);

    /**
     * Return the most recent N jobs across a workspace.
     */
    List<IndexJob> findTop50ByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    /**
     * Used to enforce "one running job per (workspace, document)" so a
     * user can't accidentally queue three indexings on the same doc at
     * once. Returns true if any PENDING or RUNNING job already exists.
     */
    @Query("""
        SELECT COUNT(j) > 0 FROM IndexJob j
        WHERE j.workspaceId = :workspaceId
          AND j.documentId = :documentId
          AND j.status IN (com.livingdocs.modules.ai.indexing.IndexJobStatus.PENDING,
                           com.livingdocs.modules.ai.indexing.IndexJobStatus.RUNNING)
        """)
    boolean existsActiveForDocument(@Param("workspaceId") UUID workspaceId,
                                    @Param("documentId") UUID documentId);

    /**
     * "Is there already an active reindex-all job running for this
     * workspace?" — used to debounce full-workspace reindexes.
     */
    @Query("""
        SELECT COUNT(j) > 0 FROM IndexJob j
        WHERE j.workspaceId = :workspaceId
          AND j.kind = com.livingdocs.modules.ai.indexing.IndexJobKind.REINDEX_ALL
          AND j.status IN (com.livingdocs.modules.ai.indexing.IndexJobStatus.PENDING,
                           com.livingdocs.modules.ai.indexing.IndexJobStatus.RUNNING)
        """)
    boolean existsActiveReindexAll(@Param("workspaceId") UUID workspaceId);

    /**
     * Admin: list all jobs across all workspaces, newest first.
     */
    Page<IndexJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}