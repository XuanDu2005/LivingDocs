package com.livingdocs.modules.version.dto;

/**
 * Lightweight projection of a version for the change-log timeline.
 * Excludes {@code bodyMarkdown} to keep the timeline payload small.
 */
public record DocumentVersionSummary(
        java.util.UUID id,
        Integer versionNumber,
        String changeSummary,
        com.livingdocs.modules.version.model.ActorRole actorRole,
        java.util.UUID actorUserId,
        String sourceCommitSha,
        String sourceCommitShort,
        java.util.UUID sourcePrId,
        com.livingdocs.modules.version.model.VersionStatus status,
        Float confidenceScore,
        java.time.OffsetDateTime createdAt
) {
    public static DocumentVersionSummary from(com.livingdocs.modules.version.model.DocumentVersion v) {
        return from(v, null);
    }

    public static DocumentVersionSummary from(com.livingdocs.modules.version.model.DocumentVersion v,
                                              String shortSha) {
        return new DocumentVersionSummary(
                v.getId(),
                v.getVersionNumber(),
                v.getChangeSummary(),
                v.getActorRole(),
                v.getActorUserId(),
                v.getSourceCommitSha(),
                shortSha,
                v.getSourcePrId(),
                v.getStatus(),
                v.getConfidenceScore(),
                v.getCreatedAt()
        );
    }
}
