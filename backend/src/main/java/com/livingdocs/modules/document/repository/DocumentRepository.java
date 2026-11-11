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

    /**
     * Every document of a workspace that belongs to one repository.
     *
     * <p>Used to resolve the repository axis of the change log: a
     * version belongs to a repository through its document, so the
     * repository filter has to go through the document table.
     */
    List<Document> findAllByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId);

    List<Document> findAllByWorkspaceIdAndDocTypeOrderByUpdatedAtDesc(UUID workspaceId, String docType);

    List<Document> findAllByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndStatus(UUID workspaceId, DocumentStatus status);

    /**
     * How many documents this user owns.
     *
     * <p>Used by the administrator's "delete user" pre-check: the
     * {@code documents.owner_id} foreign key is {@code ON DELETE
     * RESTRICT}, so an account that still owns a document cannot be
     * deleted until the ownership is transferred.
     */
    long countByOwnerId(UUID ownerId);
}