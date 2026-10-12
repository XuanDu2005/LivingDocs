package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.Commit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommitRepository extends JpaRepository<Commit, UUID> {
    Optional<Commit> findByRepositoryIdAndSha(UUID repositoryId, String sha);

    Optional<Commit> findFirstBySha(String sha);

    List<Commit> findAllByRepositoryIdOrderByAuthoredAtDesc(UUID repositoryId, Pageable pageable);

    List<Commit> findAllByRepositoryIdAndAuthoredAtAfterOrderByAuthoredAtDesc(
            UUID repositoryId, OffsetDateTime after);

    long countByRepositoryId(UUID repositoryId);
}
