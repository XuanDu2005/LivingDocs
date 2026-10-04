package com.livingdocs.modules.document.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for committing a new version of a document (human edit).
 */
public record CreateVersionRequest(
        @NotBlank String bodyMarkdown,
        String changeSummary
) {}