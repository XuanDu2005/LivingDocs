package com.livingdocs.modules.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for creating a documentation template.
 *
 * <p>{@code workspaceId} is null for global library templates.
 * {@code bodyJson} must be valid JSON describing sections / placeholders.
 */
public record CreateDocTemplateRequest(
        UUID workspaceId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 140) String slug,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 40) String docType,
        @NotBlank String bodyJson,
        boolean isDefault
) {}