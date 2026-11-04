package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.RepositoryModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryModuleRepository extends JpaRepository<RepositoryModule, UUID> {

    List<RepositoryModule> findByRepositoryIdOrderByModulePath(UUID repositoryId);

    List<RepositoryModule> findByWorkspaceIdOrderByModulePath(UUID workspaceId);

    Optional<RepositoryModule> findByRepositoryIdAndModulePath(UUID repositoryId, String modulePath);

    void deleteByRepositoryId(UUID repositoryId);
}
