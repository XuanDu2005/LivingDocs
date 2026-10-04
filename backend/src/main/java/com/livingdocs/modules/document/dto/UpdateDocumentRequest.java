package com.livingdocs.modules.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for updating an existing document's metadata (NOT its body — body
 * changes go through {@link com.livingdocs.modules.version.service.DocumentVersionService}).
 */
public record UpdateDocumentRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 1000) String summary,
        UUID repositoryId,
        UUID templateId,
        boolean autoUpdateEnabled
) {}