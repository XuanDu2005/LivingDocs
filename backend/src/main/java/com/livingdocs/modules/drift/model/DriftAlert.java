package com.livingdocs.modules.drift.model;

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
 * One row in the documentation drift alert log.
 *
 * <p>Each alert bundles:
 * <ul>
 *     <li>which workspace + repository + document it concerns</li>
 *     <li>which kind of drift (referential / signature / semantic)</li>
 *     <li>how severe the impact is</li>
 *     <li>grounding evidence (commit, diff, file path) as JSON</li>
 *     <li>optional AI-proposed documentation update text</li>
 *     <li>resolution status</li>
 * </ul>
 */
@Entity
@Table(
        name = "drift_alerts",
        indexes = {
                @Index(name = "idx_drift_workspace", columnList = "workspace_id"),
                @Index(name = "idx_drift_repository", columnList = "repository_id"),
                @Index(name = "idx_drift_document", columnList = "document_id"),
                @Index(name = "idx_drift_status", columnList = "resolution_status"),
                @Index(name = "idx_drift_severity", columnList = "severity"),
                @Index(name = "idx_drift_detected", columnList = "detected_at DESC")
        }
)
public class DriftAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "pull_request_id")
    private UUID pullRequestId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "drift_kind", nullable = false, length = 40)
    private DriftKind driftKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private DriftSeverity severity;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 24)
    private DriftResolution resolutionStatus;

    @Column(name = "ai_suggestion", columnDefinition = "text")
    private String aiSuggestion;

    @Column(name = "confidence_score")
    private Float confidenceScore;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    protected DriftAlert() {
        // JPA
    }

    public DriftAlert(UUID workspaceId, UUID repositoryId, UUID pullRequestId, UUID documentId,
                      DriftKind driftKind, DriftSeverity severity, String title,
                      String description, String evidence, String aiSuggestion,
                      Float confidenceScore) {
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.pullRequestId = pullRequestId;
        this.documentId = documentId;
        this.driftKind = driftKind;
        this.severity = severity;
        this.title = title;
        this.description = description;
        this.evidence = evidence;
        this.aiSuggestion = aiSuggestion;
        this.confidenceScore = confidenceScore;
        this.resolutionStatus = DriftResolution.OPEN;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.detectedAt == null) this.detectedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getPullRequestId() { return pullRequestId; }
    public UUID getDocumentId() { return documentId; }
    public DriftKind getDriftKind() { return driftKind; }
    public DriftSeverity getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getEvidence() { return evidence; }
    public DriftResolution getResolutionStatus() { return resolutionStatus; }
    public String getAiSuggestion() { return aiSuggestion; }
    public Float getConfidenceScore() { return confidenceScore; }
    public OffsetDateTime getDetectedAt() { return detectedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public UUID getResolvedBy() { return resolvedBy; }

    public void setResolutionStatus(DriftResolution status) { this.resolutionStatus = status; }
    public void setResolvedAt(OffsetDateTime at) { this.resolvedAt = at; }
    public void setResolvedBy(UUID userId) { this.resolvedBy = userId; }
    public void setAiSuggestion(String suggestion) { this.aiSuggestion = suggestion; }
}