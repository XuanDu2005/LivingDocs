package com.livingdocs.modules.version.repository;

import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}