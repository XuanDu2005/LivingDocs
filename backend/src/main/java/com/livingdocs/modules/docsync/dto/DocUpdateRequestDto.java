package com.livingdocs.modules.docsync.dto;

import com.livingdocs.modules.docsync.model.DocUpdateRequest;
import com.livingdocs.modules.docsync.model.DocUpdateStatus;

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
        String appliedPrUrl
) {
    public static DocUpdateRequestDto from(DocUpdateRequest r) {
        return new DocUpdateRequestDto(
                r.getId(),
                r.getWorkspaceId(),
                r.getRepositoryId(),
                r.getDocumentId(),
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
                r.getAppliedPrUrl()
        );
    }
}
