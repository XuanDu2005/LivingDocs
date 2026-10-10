package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.livingdocs.modules.github.model.Repository.Status;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    List<Repository> findByWorkspaceIdOrderByConnectedAtDesc(UUID workspaceId);
    Optional<Repository> findByGithubId(Long githubId);
    Optional<Repository> findByWorkspaceIdAndGithubId(UUID workspaceId, Long githubId);

    /**
     * Cross-tenant list ordered by most-recent activity. Drives the
     * admin "Repositories" browser. We sort by {@code lastSyncedAt}
     * desc with a nulls-last fallback so the most-recently active
     * repositories float to the top of the table.
     */
    List<Repository> findAllByOrderByLastSyncedAtDescConnectedAtDesc();

    /**
     * Aggregated status counts for the admin overview header.
     */
    long countByStatus(Status status);
}