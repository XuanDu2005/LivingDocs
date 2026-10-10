package com.livingdocs.modules.admin.dto;

import com.livingdocs.modules.admin.model.PlatformLanguage;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlatformLanguageResponse(
    UUID id,
    String languageCode,
    String languageName,
    boolean enabled,
    String defaultPrompt,
    int sortOrder,
    OffsetDateTime updatedAt
) {
    public static PlatformLanguageResponse from(PlatformLanguage p) {
        return new PlatformLanguageResponse(
                p.getId(),
                p.getLanguageCode(),
                p.getLanguageName(),
                p.isEnabled(),
                p.getDefaultPrompt(),
                p.getSortOrder(),
                p.getUpdatedAt()
        );
    }
}
