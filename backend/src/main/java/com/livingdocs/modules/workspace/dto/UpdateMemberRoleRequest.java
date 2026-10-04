package com.livingdocs.modules.workspace.dto;

import com.livingdocs.modules.workspace.model.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(@NotNull WorkspaceRole role) {
}