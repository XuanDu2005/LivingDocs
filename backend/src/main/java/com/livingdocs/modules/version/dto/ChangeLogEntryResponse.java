package com.livingdocs.modules.version.dto;

import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the workspace-level change log.
 *
 * <p>Unlike {@link DocumentVersionResponse} this projection names the
 * document and the repository the version belongs to, and it resolves
 * the display name of the actor. A change log that only carries ids
 * forces the reader to open every document to find out what they are
 * looking at; the whole point of the surface is to answer "what
 * changed, where, and who did it" in one table.
 */
public record ChangeLogEntryResponse(
        UUID id,
        UUID documentId,
        String documentTitle,
        String documentSlug,
        UUID repositoryId,
        Integer versionNumber,
        ActorRole actorRole,
        UUID actorUserId,
        /** The resolved display name of the actor, or {@code null} when it cannot be resolved. */
        String actorName,
        String changeSummary,
        VersionStatus status,
        String sourceCommitSha,
        UUID sourcePrId,
        /** The ticket the change belongs to, e.g. {@code PROJ-123}. */
        String sourceTicketRef,
        Float confidenceScore,
        OffsetDateTime createdAt) {

    public static ChangeLogEntryResponse from(DocumentVersion v,
                                              String documentTitle,
                                              String documentSlug,
                                              UUID repositoryId,
                                              String actorName) {
        return new ChangeLogEntryResponse(
                v.getId(),
                v.getDocumentId(),
                documentTitle,
                documentSlug,
                repositoryId,
                v.getVersionNumber(),
                v.getActorRole(),
                v.getActorUserId(),
                actorName,
                v.getChangeSummary(),
                v.getStatus(),
                v.getSourceCommitSha(),
                v.getSourcePrId(),
                v.getSourceTicketRef(),
                v.getConfidenceScore(),
                v.getCreatedAt());
    }
}
