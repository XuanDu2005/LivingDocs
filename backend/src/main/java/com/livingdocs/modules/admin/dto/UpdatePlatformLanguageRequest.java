package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin payload for creating / updating a {@link com.livingdocs.modules.admin.model.PlatformLanguage}.
 */
public record UpdatePlatformLanguageRequest(
        @NotBlank @Size(max = 80) String languageName,
        boolean enabled,
        String defaultPrompt,
        @Min(0) int sortOrder
) {}
