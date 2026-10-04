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
}