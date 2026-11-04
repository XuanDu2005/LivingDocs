package com.livingdocs.modules.github.dto;

/**
 * A module (directory / package) of a repository whose documents can be
 * excluded from AI auto-update.
 */
public record RepositoryModuleResponse(
        String id,
        String repositoryId,
        String workspaceId,
        String modulePath,
        String displayName,
        boolean autoUpdateEnabled,
        String createdAt,
        String updatedAt
) {
    public static RepositoryModuleResponse from(
            com.livingdocs.modules.github.model.RepositoryModule m) {
        return new RepositoryModuleResponse(
                m.getId().toString(),
                m.getRepositoryId().toString(),
                m.getWorkspaceId().toString(),
                m.getModulePath(),
                m.getDisplayName(),
                m.isAutoUpdateEnabled(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().toString(),
                m.getUpdatedAt() == null ? null : m.getUpdatedAt().toString());
    }
}
