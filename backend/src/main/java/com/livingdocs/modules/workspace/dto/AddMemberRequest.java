package com.livingdocs.modules.workspace.dto;

import com.livingdocs.modules.workspace.model.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddMemberRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull WorkspaceRole role
) {
}