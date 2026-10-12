package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.GithubConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GithubConnectionRepository extends JpaRepository<GithubConnection, UUID> {
    Optional<GithubConnection> findByUserId(UUID userId);
    Optional<GithubConnection> findByGithubUserId(Long githubUserId);
    List<GithubConnection> findAllByStatus(GithubConnection.Status status);
}