package com.livingdocs.modules.ai.bulk;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * Tracks an asynchronous "generate documentation for every undocumented
 * entity in this repository" run.
 *
 * <p>The row is created synchronously when the user clicks
 * <em>Generate All Docs</em> and the endpoint answers 202. A background
 * worker then mutates it in place: the status moves to
 * {@link BulkGenerationJobStatus#RUNNING}, the counters advance as each
 * entity is processed, and the job ends as
 * {@link BulkGenerationJobStatus#SUCCEEDED},
 * {@link BulkGenerationJobStatus#PARTIAL} or
 * {@link BulkGenerationJobStatus#FAILED}.
 *
 * <p>The per-entity outcome is kept in {@code resultJson} so a partially
 * failed run still shows the user which documents were written and which
 * entities failed — a bulk job that silently dropped five entities would
 * look like it succeeded.
 */
@Entity
@Table(
        name = "bulk_generation_jobs",
        indexes = {
                @Index(name = "idx_bulk_gen_jobs_workspace",
                        columnList = "workspace_id, created_at"),
                @Index(name = "idx_bulk_gen_jobs_repository",
                        columnList = "repository_id, created_at"),
                @Index(name = "idx_bulk_gen_jobs_status", columnList = "status")
        }
)
public class BulkGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "template", length = 140)
    private String template;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BulkGenerationJobStatus status = BulkGenerationJobStatus.PENDING;

    @Column(name = "total_entities", nullable = false)
    private int totalEntities = 0;

    @Column(name = "processed_entities", nullable = false)
    private int processedEntities = 0;

    @Column(name = "generated_documents", nullable = false)
    private int generatedDocuments = 0;

    @Column(name = "skipped_entities", nullable = false)
    private int skippedEntities = 0;

    @Column(name = "failed_entities", nullable = false)
    private int failedEntities = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * The per-entity outcome: which documents were written, which entities
     * were skipped and what went wrong for the failures.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    public BulkGenerationJobStatus getStatus() { return status; }
    public void setStatus(BulkGenerationJobStatus status) { this.status = status; }
    public int getTotalEntities() { return totalEntities; }
    public void setTotalEntities(int totalEntities) { this.totalEntities = totalEntities; }
    public int getProcessedEntities() { return processedEntities; }
    public void setProcessedEntities(int processedEntities) { this.processedEntities = processedEntities; }
    public int getGeneratedDocuments() { return generatedDocuments; }
    public void setGeneratedDocuments(int generatedDocuments) { this.generatedDocuments = generatedDocuments; }
    public int getSkippedEntities() { return skippedEntities; }
    public void setSkippedEntities(int skippedEntities) { this.skippedEntities = skippedEntities; }
    public int getFailedEntities() { return failedEntities; }
    public void setFailedEntities(int failedEntities) { this.failedEntities = failedEntities; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
