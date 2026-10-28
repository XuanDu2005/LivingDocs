package com.livingdocs.modules.docsync.repository;

import com.livingdocs.modules.docsync.model.DocUpdateRequest;
import com.livingdocs.modules.docsync.model.DocUpdateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocUpdateRequestRepository extends JpaRepository<DocUpdateRequest, UUID> {

    List<DocUpdateRequest> findByRepositoryIdAndStatusOrderByRequestedAtDesc(
            UUID repositoryId, DocUpdateStatus status);

    List<DocUpdateRequest> findByRepositoryIdOrderByRequestedAtDesc(UUID repositoryId);

    List<DocUpdateRequest> findByWorkspaceIdAndStatusOrderByRequestedAtDesc(
            UUID workspaceId, DocUpdateStatus status);

    long countByWorkspaceIdAndStatus(UUID workspaceId, DocUpdateStatus status);

    /**
     * Find an existing proposal for the same file that is still awaiting
     * a decision. Used to avoid queueing the same file twice when the
     * user scans a repository repeatedly.
     */
    java.util.Optional<DocUpdateRequest> findFirstByRepositoryIdAndFilePathAndStatusIn(
            UUID repositoryId, String filePath, java.util.Collection<DocUpdateStatus> statuses);
}
