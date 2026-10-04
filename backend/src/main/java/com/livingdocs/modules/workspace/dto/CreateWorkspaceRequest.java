package com.livingdocs.modules.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank
        @Size(min = 2, max = 140)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$",
                message = "slug must be lowercase, alphanumeric and may contain dashes")
        String slug,
        @Size(max = 500) String description
) {
}