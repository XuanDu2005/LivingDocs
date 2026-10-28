package com.livingdocs.modules.ai.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable record of one asynchronous AI generation run.
 *
 * <p>The generation pipeline is: HTTP request → {@code DocGenerationRequestedEvent}
 * → AI worker → AI service → {@code DocGenerationCompletedEvent} →
 * persistence worker → {@code document_versions} row.
 *
 * <p>This entity is the join between those stages. It gives the UI a
 * "generation in progress" indicator, records the token cost of the
 * run, and — via the unique {@code correlation_id} — makes the whole
 * flow idempotent: a Kafka redelivery finds the existing job instead
 * of creating a duplicate version.
 */
@Entity
@Table(
        name = "doc_generation_jobs",
        indexes = {
                @Index(name = "idx_doc_gen_jobs_document", columnList = "document_id"),
                @Index(name = "idx_doc_gen_jobs_workspace", columnList = "workspace_id"),
                @Index(name = "idx_doc_gen_jobs_status", columnList = "status"),
                @Index(name = "idx_doc_gen_jobs_created", columnList = "created_at DESC")
        }
)
public class DocGenerationJob {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "template", length = 140)
    private String template;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.RUNNING;

    @Column(name = "document_version_id")
    private UUID documentVersionId;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    protected DocGenerationJob() {
        // JPA
    }

    public DocGenerationJob(UUID id,
                            UUID workspaceId,
                            UUID documentId,
                            UUID correlationId,
                            UUID requestedBy,
                            String template) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.documentId = documentId;
        this.correlationId = correlationId;
        this.requestedBy = requestedBy;
        this.template = template;
        this.status = Status.RUNNING;
        this.createdAt = OffsetDateTime.now();
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getDocumentId() { return documentId; }
    public UUID getCorrelationId() { return correlationId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getTemplate() { return template; }
    public Status getStatus() { return status; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public String getErrorMessage() { return errorMessage; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }

    public void succeed(UUID versionId, int promptTokens, int completionTokens) {
        this.status = Status.SUCCEEDED;
        this.documentVersionId = versionId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String errorMessage) {
        this.status = Status.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = OffsetDateTime.now();
    }

    public void skip(String reason) {
        this.status = Status.SKIPPED;
        this.errorMessage = reason;
        this.completedAt = OffsetDateTime.now();
    }
}