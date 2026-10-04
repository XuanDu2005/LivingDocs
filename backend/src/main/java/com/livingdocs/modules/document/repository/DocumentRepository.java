package com.livingdocs.modules.document.repository;

import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByWorkspaceIdAndSlug(UUID workspaceId, String slug);

    List<Document> findAllByWorkspaceIdOrderByUpdatedAtDesc(UUID workspaceId);

    List<Document> findAllByWorkspaceIdAndStatusOrderByUpdatedAtDesc(UUID workspaceId, DocumentStatus status);

    List<Document> findAllByRepositoryIdOrderByUpdatedAtDesc(UUID repositoryId);

    List<Document> findAllByWorkspaceIdAndDocTypeOrderByUpdatedAtDesc(UUID workspaceId, String docType);

    List<Document> findAllByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndStatus(UUID workspaceId, DocumentStatus status);
}