package com.livingdocs.modules.code.repository;

import com.livingdocs.modules.code.model.CodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CodeEntityRepository extends JpaRepository<CodeEntity, UUID> {

    List<CodeEntity> findAllByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    List<CodeEntity> findAllByRepositoryIdOrderByIngestedAtDesc(UUID repositoryId);

    List<CodeEntity> findAllBySimpleNameIgnoreCase(String simpleName);

    long countByRepositoryId(UUID repositoryId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT e FROM CodeEntity e 
        WHERE e.repositoryId = :repositoryId 
        AND e.id NOT IN (SELECT link.codeEntityId FROM CodeDocumentLink link)
        ORDER BY e.filePath ASC, e.startLine ASC
    """)
    List<CodeEntity> findCoverageGaps(@org.springframework.data.repository.query.Param("repositoryId") UUID repositoryId);
}