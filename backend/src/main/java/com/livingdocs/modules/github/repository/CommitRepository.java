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

    /**
     * The oldest commit the platform has ingested for a repository.
     *
     * <p>Used as the fallback anchor of a drift comparison: when a
     * document's own version does not record the commit it was written
     * against, the earliest revision the platform knows of is the only
     * point it can speak about.
     */
    Optional<Commit> findFirstByRepositoryIdOrderByAuthoredAtAsc(UUID repositoryId);

    /**
     * The newest commit that was authored at or before a point in time.
     *
     * <p>This is the revision of the code as it stood at that moment —
     * the anchor a drift comparison needs when the document itself does
     * not record the commit it was written against.
     */
    Optional<Commit> findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
            UUID repositoryId, OffsetDateTime moment);

    List<Commit> findAllByRepositoryIdAndAuthoredAtAfterOrderByAuthoredAtDesc(
            UUID repositoryId, OffsetDateTime after);

    long countByRepositoryId(UUID repositoryId);
}
