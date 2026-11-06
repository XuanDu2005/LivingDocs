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
        /**
         * The user who pushed this version into the review queue.
         *
         * <p>The UI needs it to hide the review controls on a version
         * the viewer submitted themselves: the self-review guard refuses
         * that verdict, so a button that always fails must not be
         * rendered.
         */
        java.util.UUID submittedByUserId,
        String sourceCommitSha,
        String sourceCommitShort,
        java.util.UUID sourcePrId,
        /** The ticket the change belongs to, e.g. {@code PROJ-123}. */
        String sourceTicketRef,
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
                v.getSubmittedByUserId(),
                v.getSourceCommitSha(),
                shortSha,
                v.getSourcePrId(),
                v.getSourceTicketRef(),
                v.getStatus(),
                v.getConfidenceScore(),
                v.getCreatedAt()
        );
    }
}
