package com.livingdocs.modules.ai.indexing;

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
 * Tracks an asynchronous indexing job against the AI knowledge base.
 *
 * <p>Rows are created synchronously when a user enqueues an indexing
 * request (single document or full-workspace reindex). They are then
 * mutated by {@link IndexingService} running on a background executor:
 * status transitions, progress counters, and timing are all updated in
 * place.
 *
 * <p>The entity intentionally mirrors the
 * {@code indexing_jobs} table created in
 * {@code V7__indexing_jobs.sql} — every column here has a corresponding
 * {@code @Column} mapping and the names line up with the schema so a
 * Hibernate reading the table will pick the columns up without any
 * alias tricks.
 */
@Entity
@Table(
        name = "indexing_jobs",
        indexes = {
                @Index(name = "idx_indexing_jobs_workspace_status",
                        columnList = "workspace_id, status, created_at"),
                @Index(name = "idx_indexing_jobs_document",
                        columnList = "document_id, created_at")
        }
)
public class IndexJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /**
     * Null when the job targets every document in the workspace
     * ({@link IndexJobKind#REINDEX_ALL}).
     */
    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IndexJobStatus status = IndexJobStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private IndexJobKind kind;

    @Column(name = "total_targets", nullable = false)
    private int totalTargets = 0;

    @Column(name = "processed_targets", nullable = false)
    private int processedTargets = 0;

    @Column(name = "chunks_indexed", nullable = false)
    private int chunksIndexed = 0;

    @Column(name = "failed_targets", nullable = false)
    private int failedTargets = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public IndexJobStatus getStatus() { return status; }
    public void setStatus(IndexJobStatus status) { this.status = status; }
    public IndexJobKind getKind() { return kind; }
    public void setKind(IndexJobKind kind) { this.kind = kind; }
    public int getTotalTargets() { return totalTargets; }
    public void setTotalTargets(int totalTargets) { this.totalTargets = totalTargets; }
    public int getProcessedTargets() { return processedTargets; }
    public void setProcessedTargets(int processedTargets) { this.processedTargets = processedTargets; }
    public int getChunksIndexed() { return chunksIndexed; }
    public void setChunksIndexed(int chunksIndexed) { this.chunksIndexed = chunksIndexed; }
    public int getFailedTargets() { return failedTargets; }
    public void setFailedTargets(int failedTargets) { this.failedTargets = failedTargets; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}