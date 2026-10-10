package com.livingdocs.modules.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateWorkspaceLanguageRequest(
    @NotBlank
    @Size(max = 40)
    String languageCode,

    @NotBlank
    @Size(max = 80)
    String languageName,

    boolean enabled,

    @Size(max = 8000)
    String customPrompt
) {}
