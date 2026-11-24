package com.livingdocs.modules.docsync.dto;

import com.livingdocs.modules.docsync.model.DocUpdateRequest;
import com.livingdocs.modules.docsync.model.DocUpdateStatus;
import com.livingdocs.modules.version.model.VersionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public-facing view of a {@link DocUpdateRequest}. Exposed to the
 * frontend so reviewers can render the proposal list, preview the diff,
 * and approve/reject without exposing internal fields.
 */
public record DocUpdateRequestDto(
        UUID id,
        UUID workspaceId,
        UUID repositoryId,
        UUID documentId,
        UUID documentVersionId,
        String filePath,
        String triggerCommitSha,
        String triggerBranch,
        String currentContent,
        String proposedContent,
        String diffSummary,
        DocUpdateStatus status,
        UUID requestedByUserId,
        OffsetDateTime requestedAt,
        UUID reviewedByUserId,
        OffsetDateTime reviewedAt,
        String reviewComment,
        OffsetDateTime appliedAt,
        String appliedPrUrl,
        /**
         * The status of the version this proposal materialised into.
         *
         * <p>{@code null} for a proposal that has no version — a file in
         * the repository that no LivingDocs document publishes.
         *
         * <p>The UI needs it to tell the reviewer where the proposal
         * stands in the two-tier workflow: the proposal's own status only
         * records the Staff decision, while the version carries the
         * Manager decision that the push to Git depends on.
         */
        VersionStatus versionStatus
) {
    public static DocUpdateRequestDto from(DocUpdateRequest r) {
        return from(r, null);
    }

    /**
     * The projection, with the status of the materialised version.
     *
     * @param versionStatus the status of the linked version, or
     *                       {@code null} when there is none
     */
    public static DocUpdateRequestDto from(DocUpdateRequest r, VersionStatus versionStatus) {
        return new DocUpdateRequestDto(
                r.getId(),
                r.getWorkspaceId(),
                r.getRepositoryId(),
                r.getDocumentId(),
                r.getDocumentVersionId(),
                r.getFilePath(),
                r.getTriggerCommitSha(),
                r.getTriggerBranch(),
                r.getCurrentContent(),
                r.getProposedContent(),
                r.getDiffSummary(),
                r.getStatus(),
                r.getRequestedByUserId(),
                r.getRequestedAt(),
                r.getReviewedByUserId(),
                r.getReviewedAt(),
                r.getReviewComment(),
                r.getAppliedAt(),
                r.getAppliedPrUrl(),
                versionStatus
        );
    }
}
