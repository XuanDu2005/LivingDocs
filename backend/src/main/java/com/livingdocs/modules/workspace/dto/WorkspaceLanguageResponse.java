package com.livingdocs.modules.workspace.dto;

import com.livingdocs.modules.workspace.model.WorkspaceLanguage;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkspaceLanguageResponse(
    UUID id,
    UUID workspaceId,
    String languageCode,
    String languageName,
    boolean enabled,
    String customPrompt,
    OffsetDateTime updatedAt
) {
    public static WorkspaceLanguageResponse from(WorkspaceLanguage l) {
        return new WorkspaceLanguageResponse(
            l.getId(),
            l.getWorkspaceId(),
            l.getLanguageCode(),
            l.getLanguageName(),
            l.isEnabled(),
            l.getCustomPrompt(),
            l.getUpdatedAt()
        );
    }
}
