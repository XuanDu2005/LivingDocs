package com.livingdocs.modules.version.repository;

import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    List<DocumentVersion> findAllByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    Optional<DocumentVersion> findByDocumentIdAndVersionNumber(UUID documentId, Integer versionNumber);

    /**
     * Load a version with a pessimistic write lock.
     *
     * <p>Two Staff reviewers hitting "approve" on the same version at
     * the same time would otherwise both read the same state and both
     * write a verdict — the database would accept both rows and the
     * workflow state machine would then have two conflicting opinions
     * about the same version. The partial unique index added in V40 is
     * the last line of defence, but taking the lock here means the
     * second caller waits and then sees the first caller's result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DocumentVersion v where v.id = :id")
    Optional<DocumentVersion> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Every version of the given documents that currently sits in one of
     * the given statuses, newest submission first.
     *
     * <p>Replaces the previous "load every document, then load every
     * version of every document and filter in memory" approach, which
     * ran one query per document.
     */
    List<DocumentVersion> findAllByDocumentIdInAndStatusInOrderBySubmittedAtDesc(
            Collection<UUID> documentIds, Collection<VersionStatus> statuses);

    long countByDocumentId(UUID documentId);

    List<DocumentVersion> findAllByDocumentIdAndStatusOrderByCreatedAtDesc(UUID documentId, VersionStatus status);

    /** All version numbers for a document, ordered newest first. Used for the timeline UI. */
    List<Integer> findDistinctVersionNumberByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    /**
     * Every version in the given documents that the user submitted for
     * review, newest submission first.
     *
     * <p>Used by the "my submissions" bucket of the review queue: an
     * author needs to see what happened to the versions they handed over,
     * and the only reliable link between the two is the
     * {@code submitted_by_user_id} column.
     */
    List<DocumentVersion> findAllBySubmittedByUserIdAndDocumentIdInOrderBySubmittedAtDesc(
            UUID submittedByUserId, Collection<UUID> documentIds);
}
