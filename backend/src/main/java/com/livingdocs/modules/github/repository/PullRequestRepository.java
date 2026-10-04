package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {
    List<PullRequest> findByRepositoryIdOrderByUpdatedAtDesc(UUID repositoryId);
    Optional<PullRequest> findByRepositoryIdAndGithubPrNumber(UUID repositoryId, Long number);
}