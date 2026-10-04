package com.livingdocs.modules.github.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /workspaces/{id}/repositories} — selects an
 * already-listed GitHub repository and attaches it to a workspace.
 */
public record ConnectRepositoryRequest(
        @NotNull(message = "githubId is required")
        @Min(value = 1, message = "githubId must be positive")
        Long githubId,
        String defaultBranch) {
}