package com.livingdocs.modules.link.dto;

import com.livingdocs.modules.link.model.CodeDocumentLink;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CodeDocumentLinkResponse(
        UUID id,
        UUID documentId,
        UUID codeEntityId,
        String linkKind,
        Float confidence,
        OffsetDateTime createdAt
) {
    public static CodeDocumentLinkResponse from(CodeDocumentLink l) {
        return new CodeDocumentLinkResponse(
                l.getId(), l.getDocumentId(), l.getCodeEntityId(),
                l.getLinkKind(), l.getConfidence(), l.getCreatedAt());
    }
}