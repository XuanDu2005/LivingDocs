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
        /**
         * The user who pushed this version into the review queue.
         *
         * <p>Distinct from {@code actorUserId}: when a reviewer revises
         * a pending version in place, the actor is the reviewer but the
         * submission still belongs to the author who asked for the
         * review.
         */
        UUID submittedByUserId,
        /** When the version was submitted for review. */
        OffsetDateTime submittedAt,
        String sourceCommitSha,
        UUID sourcePrId,
        /** The ticket the change belongs to, e.g. {@code PROJ-123}. */
        String sourceTicketRef,
        UUID templateId,
        VersionStatus status,
        Float confidenceScore,
        OffsetDateTime createdAt,
        CommitSummaryDto triggerCommit) {
    public static DocumentVersionResponse from(DocumentVersion v) {
        return from(v, null);
    }

    public static DocumentVersionResponse from(DocumentVersion v, CommitSummaryDto triggerCommit) {
        return new DocumentVersionResponse(
                v.getId(),
                v.getDocumentId(),
                v.getVersionNumber(),
                v.getBodyMarkdown(),
                v.getChangeSummary(),
                v.getActorRole(),
                v.getActorUserId(),
                v.getSubmittedByUserId(),
                v.getSubmittedAt(),
                v.getSourceCommitSha(),
                v.getSourcePrId(),
                v.getSourceTicketRef(),
                v.getTemplateId(),
                v.getStatus(),
                v.getConfidenceScore(),
                v.getCreatedAt(),
                triggerCommit
        );
    }
}
