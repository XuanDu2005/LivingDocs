package com.livingdocs.modules.workspace.repository;

import com.livingdocs.modules.workspace.model.WorkspaceDocTypePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceDocTypePolicyRepository
        extends JpaRepository<WorkspaceDocTypePolicy, UUID> {

    List<WorkspaceDocTypePolicy> findAllByWorkspaceIdOrderByDocTypeAsc(UUID workspaceId);

    Optional<WorkspaceDocTypePolicy> findByWorkspaceIdAndDocType(UUID workspaceId, String docType);
}
