package com.livingdocs.modules.version.dto;

import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API projection of a document version (one row in the change log).
 *
 * <p>The full {@code bodyMarkdown} is included by default because the UI
 * needs it to render the document. A list-only projection can be derived
 * client-side by mapping away {@code bodyMarkdown}.
 */
public record DocumentVersionResponse(
        UUID id,
        UUID documentId,
        Integer versionNumber,
        String bodyMarkdown,
        String changeSummary,
        ActorRole actorRole,
        UUID actorUserId,
        String sourceCommitSha,
        UUID sourcePrId,
        UUID templateId,
        VersionStatus status,
        Float confidenceScore,
        OffsetDateTime createdAt
) {
    public static DocumentVersionResponse from(DocumentVersion v) {
        return new DocumentVersionResponse(
                v.getId(),
                v.getDocumentId(),
                v.getVersionNumber(),
                v.getBodyMarkdown(),
                v.getChangeSummary(),
                v.getActorRole(),
                v.getActorUserId(),
                v.getSourceCommitSha(),
                v.getSourcePrId(),
                v.getTemplateId(),
                v.getStatus(),
                v.getConfidenceScore(),
                v.getCreatedAt()
        );
    }
}