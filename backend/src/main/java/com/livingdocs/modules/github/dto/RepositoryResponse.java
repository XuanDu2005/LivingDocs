package com.livingdocs.modules.github.dto;

import java.util.List;

/**
 * Public representation of a repository connected to a workspace.
 */
public record RepositoryResponse(
        String id,
        String workspaceId,
        Long githubId,
        String owner,
        String name,
        String fullName,
        String defaultBranch,
        String htmlUrl,
        String description,
        boolean isPrivate,
        String status,
        String connectedBy,
        String connectedAt,
        String lastSyncedAt) {
}