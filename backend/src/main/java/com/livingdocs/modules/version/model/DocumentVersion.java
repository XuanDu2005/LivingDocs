package com.livingdocs.modules.version.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable snapshot of a document at a point in time. Every body change —
 * whether produced by the AI, a Staff reviewer, or a Manager — is stored as
 * a new row here. The acting role is recorded so any modification is
 * auditable and rollback is supported.
 */
@Entity
@Table(
        name = "document_versions",
        uniqueConstraints = @UniqueConstraint(name = "uq_document_versions",
                columnNames = {"document_id", "version_number"}),
        indexes = {
                @Index(name = "idx_document_versions_doc", columnList = "document_id"),
                @Index(name = "idx_document_versions_actor_role", columnList = "actor_role"),
                @Index(name = "idx_document_versions_created_at", columnList = "created_at DESC")
        }
)
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    private String bodyMarkdown;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 16)
    private ActorRole actorRole;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "source_commit_sha", length = 80)
    private String sourceCommitSha;

    @Column(name = "source_pr_id")
    private UUID sourcePrId;

    @Column(name = "template_id")
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private VersionStatus status;

    @Column(name = "confidence_score")
    private Float confidenceScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DocumentVersion() {
        // JPA
    }

    public DocumentVersion(UUID documentId, Integer versionNumber, String bodyMarkdown,
                           String changeSummary, ActorRole actorRole, UUID actorUserId,
                           String sourceCommitSha, UUID sourcePrId, UUID templateId,
                           VersionStatus status, Float confidenceScore) {
        this.documentId = documentId;
        this.versionNumber = versionNumber;
        this.bodyMarkdown = bodyMarkdown;
        this.changeSummary = changeSummary;
        this.actorRole = actorRole;
        this.actorUserId = actorUserId;
        this.sourceCommitSha = sourceCommitSha;
        this.sourcePrId = sourcePrId;
        this.templateId = templateId;
        this.status = status;
        this.confidenceScore = confidenceScore;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public Integer getVersionNumber() { return versionNumber; }
    public String getBodyMarkdown() { return bodyMarkdown; }
    public String getChangeSummary() { return changeSummary; }
    public ActorRole getActorRole() { return actorRole; }
    public UUID getActorUserId() { return actorUserId; }
    public String getSourceCommitSha() { return sourceCommitSha; }
    public UUID getSourcePrId() { return sourcePrId; }
    public UUID getTemplateId() { return templateId; }
    public VersionStatus getStatus() { return status; }
    public Float getConfidenceScore() { return confidenceScore; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setStatus(VersionStatus status) { this.status = status; }
    public void setConfidenceScore(Float score) { this.confidenceScore = score; }
}