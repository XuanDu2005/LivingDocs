package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    List<Repository> findByWorkspaceIdOrderByConnectedAtDesc(UUID workspaceId);
    Optional<Repository> findByGithubId(Long githubId);
    Optional<Repository> findByWorkspaceIdAndGithubId(UUID workspaceId, Long githubId);
}