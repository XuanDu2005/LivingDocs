package com.livingdocs.modules.review.model;

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
 * One review pass on a document version.
 *
 * <p>{@code reviewerRole} is the platform role the reviewer acted under
 * (Staff or Manager). The same version may receive multiple review rows
 * (e.g. Staff approves, then Manager approves) — the latest entry per
 * {@code (versionId, reviewerRole)} is the authoritative verdict.
 */
@Entity
@Table(
        name = "document_reviews",
        indexes = {
                @Index(name = "idx_document_reviews_version", columnList = "document_version_id"),
                @Index(name = "idx_document_reviews_reviewer", columnList = "reviewer_user_id")
        }
)
public class DocumentReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "document_version_id", nullable = false)
    private UUID documentVersionId;

    @Column(name = "reviewer_user_id", nullable = false)
    private UUID reviewerUserId;

    @Column(name = "reviewer_role", nullable = false, length = 16)
    private String reviewerRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 24)
    private ReviewDecision decision;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private OffsetDateTime decidedAt;

    protected DocumentReview() {
        // JPA
    }

    public DocumentReview(UUID documentVersionId, UUID reviewerUserId,
                          String reviewerRole, ReviewDecision decision, String comment) {
        this.documentVersionId = documentVersionId;
        this.reviewerUserId = reviewerUserId;
        this.reviewerRole = reviewerRole;
        this.decision = decision;
        this.comment = comment;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.decidedAt == null) this.decidedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public UUID getReviewerUserId() { return reviewerUserId; }
    public String getReviewerRole() { return reviewerRole; }
    public ReviewDecision getDecision() { return decision; }
    public String getComment() { return comment; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
}