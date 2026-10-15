package com.livingdocs.modules.docsync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A "documentation update proposal" produced when the system detects
 * drift between code and a doc file. The proposal is created
 * automatically by the push webhook handler and waits in
 * {@link DocUpdateStatus#PENDING_REVIEW} until a reviewer approves or
 * rejects it. Only {@code APPROVED} proposals are allowed to be turned
 * into a real GitHub PR.
 *
 * <p>The entity is intentionally append-only: every state transition is
 * captured by updating this row (with timestamps), giving us a full
 * audit trail of who asked, who reviewed, and when the PR landed.
 */
@Entity
@Table(
        name = "doc_update_requests",
        indexes = {
                @Index(name = "idx_doc_update_pending",
                        columnList = "workspace_id, repository_id, status, requested_at DESC"),
                @Index(name = "idx_doc_update_repo_status",
                        columnList = "repository_id, status")
        }
)
public class DocUpdateRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(name = "trigger_commit_sha", length = 80)
    private String triggerCommitSha;

    @Column(name = "trigger_branch", length = 255)
    private String triggerBranch;

    @Column(name = "current_content", columnDefinition = "TEXT")
    private String currentContent;

    @Column(name = "proposed_content", nullable = false, columnDefinition = "TEXT")
    private String proposedContent;

    @Column(name = "diff_summary", length = 1000)
    private String diffSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DocUpdateStatus status;

    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "applied_pr_url", length = 1000)
    private String appliedPrUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DocUpdateRequest() {
        // JPA
    }

    public DocUpdateRequest(UUID workspaceId, UUID repositoryId, UUID documentId,
                            String filePath, String currentContent, String proposedContent,
                            String diffSummary, String triggerCommitSha, String triggerBranch,
                            UUID requestedByUserId) {
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.documentId = documentId;
        this.filePath = filePath;
        this.currentContent = currentContent;
        this.proposedContent = proposedContent;
        this.diffSummary = diffSummary;
        this.triggerCommitSha = triggerCommitSha;
        this.triggerBranch = triggerBranch;
        this.requestedByUserId = requestedByUserId;
        this.status = DocUpdateStatus.PENDING_REVIEW;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.requestedAt == null) this.requestedAt = now;
        this.updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // ── State transitions ────────────────────────────────────────────────

    public void approve(UUID reviewerId, String comment) {
        this.status = DocUpdateStatus.APPROVED;
        this.reviewedByUserId = reviewerId;
        this.reviewedAt = OffsetDateTime.now();
        this.reviewComment = comment;
    }

    public void reject(UUID reviewerId, String comment) {
        this.status = DocUpdateStatus.REJECTED;
        this.reviewedByUserId = reviewerId;
        this.reviewedAt = OffsetDateTime.now();
        this.reviewComment = comment;
    }

    public void markApplied(String prUrl) {
        this.status = DocUpdateStatus.APPLIED;
        this.appliedAt = OffsetDateTime.now();
        this.appliedPrUrl = prUrl;
    }

    // ── Getters / setters ────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getDocumentId() { return documentId; }
    public String getFilePath() { return filePath; }
    public String getTriggerCommitSha() { return triggerCommitSha; }
    public String getTriggerBranch() { return triggerBranch; }
    public String getCurrentContent() { return currentContent; }
    public String getProposedContent() { return proposedContent; }
    public String getDiffSummary() { return diffSummary; }
    public DocUpdateStatus getStatus() { return status; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public UUID getReviewedByUserId() { return reviewedByUserId; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public String getReviewComment() { return reviewComment; }
    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public String getAppliedPrUrl() { return appliedPrUrl; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
