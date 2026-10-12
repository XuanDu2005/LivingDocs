package com.livingdocs.modules.document.repository;

import com.livingdocs.modules.document.model.DocumentTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentTagRepository extends JpaRepository<DocumentTag, UUID> {
    List<DocumentTag> findByWorkspaceIdOrderByNameAsc(UUID workspaceId);
    boolean existsByWorkspaceIdAndNameIgnoreCase(UUID workspaceId, String name);
}