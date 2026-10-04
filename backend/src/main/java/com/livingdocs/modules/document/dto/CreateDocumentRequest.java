package com.livingdocs.modules.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for creating a new documentation entity.
 */
public record CreateDocumentRequest(
        UUID repositoryId,
        UUID templateId,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 280) String slug,
        @NotBlank @Size(max = 40) String docType,
        @Size(max = 1000) String summary,
        boolean autoUpdateEnabled,
        @NotBlank String initialBody
) {}