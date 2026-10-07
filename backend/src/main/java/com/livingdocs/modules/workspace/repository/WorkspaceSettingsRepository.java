package com.livingdocs.modules.workspace.repository;

import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface WorkspaceSettingsRepository extends JpaRepository<WorkspaceSettings, UUID> {

    /**
     * Read the raw JSONB {@code extra} payload straight from PostgreSQL
     * without going through the JPA entity, so callers that only need
     * the JSON contents (e.g. the AI settings service) avoid loading
     * the whole row.
     */
    @Query(value = "SELECT extra FROM workspace_settings WHERE workspace_id = :id",
            nativeQuery = true)
    String findExtraRaw(@org.springframework.data.repository.query.Param("id") UUID id);
}
