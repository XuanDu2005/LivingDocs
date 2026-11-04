package com.livingdocs.modules.version.repository;

import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /**
     * The same set, restricted to versions that are still somewhere in
     * the review workflow. A version that was already published or
     * superseded has left the queue for good.
     */
    List<DocumentVersion> findAllBySubmittedByUserIdAndDocumentIdInAndStatusInOrderBySubmittedAtDesc(
            UUID submittedByUserId, Collection<UUID> documentIds, Collection<VersionStatus> statuses);
}
