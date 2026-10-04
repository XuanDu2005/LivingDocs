package com.livingdocs.modules.code.dto;

import com.livingdocs.modules.code.model.CodeEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CodeEntityResponse(
        UUID id,
        UUID repositoryId,
        String commitSha,
        String language,
        String entityType,
        String qualifiedName,
        String simpleName,
        String filePath,
        Integer startLine,
        Integer endLine,
        String signature,
        String docstring,
        String metadata,
        OffsetDateTime ingestedAt
) {
    public static CodeEntityResponse from(CodeEntity e) {
        return new CodeEntityResponse(
                e.getId(), e.getRepositoryId(), e.getCommitSha(),
                e.getLanguage(), e.getEntityType(), e.getQualifiedName(),
                e.getSimpleName(), e.getFilePath(),
                e.getStartLine(), e.getEndLine(),
                e.getSignature(), e.getDocstring(), e.getMetadata(),
                e.getIngestedAt());
    }
}