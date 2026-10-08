package com.livingdocs.modules.workspace.repository;

import com.livingdocs.modules.workspace.model.WorkspaceLanguage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceLanguageRepository extends JpaRepository<WorkspaceLanguage, UUID> {

    List<WorkspaceLanguage> findAllByWorkspaceIdOrderByLanguageNameAsc(UUID workspaceId);

    Optional<WorkspaceLanguage> findByWorkspaceIdAndLanguageCode(UUID workspaceId, String languageCode);
}
