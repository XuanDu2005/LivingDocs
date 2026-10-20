package com.livingdocs.modules.link.repository;

import com.livingdocs.modules.link.model.CodeDocumentLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CodeDocumentLinkRepository extends JpaRepository<CodeDocumentLink, UUID> {

    List<CodeDocumentLink> findAllByDocumentId(UUID documentId);
    List<CodeDocumentLink> findAllByCodeEntityId(UUID codeEntityId);
    Optional<CodeDocumentLink> findByDocumentIdAndCodeEntityId(UUID documentId, UUID codeEntityId);
    List<CodeDocumentLink> findByCodeEntityIdIn(Collection<UUID> codeEntityIds);

    void deleteByDocumentId(UUID documentId);
}