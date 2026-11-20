package com.livingdocs.modules.ai.analysis;

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
 * Tracks an asynchronous "analyse the whole repository and write
 * overview documentation" run.
 *
 * <p>The row is created synchronously when the user clicks
 * <em>Analyse repository</em> and the endpoint answers 202. A
 * background worker then mutates it in place: the status moves to
 * {@link RepositoryAnalysisJobStatus#RUNNING}, the counters advance as
 * the scan and the generation progress, and the job ends as
 * {@link RepositoryAnalysisJobStatus#SUCCEEDED},
 * {@link RepositoryAnalysisJobStatus#PARTIAL} or
 * {@link RepositoryAnalysisJobStatus#FAILED}.
 *
 * <p>The per-document outcome is kept in {@code resultJson} so a
 * partially failed run still shows the user which overview documents
 * were written — a repository analysis that silently dropped the
 * architecture guide would look like it succeeded.
 */
@Entity
@Table(
        name = "repository_analysis_jobs",
        indexes = {
                @Index(name = "idx_repo_analysis_jobs_workspace",
                        columnList = "workspace_id, created_at"),
                @Index(name = "idx_repo_analysis_jobs_repository",
                        columnList = "repository_id, created_at"),
                @Index(name = "idx_repo_analysis_jobs_status", columnList = "status")
        }
)
public class RepositoryAnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    /**
     * The document types this run targets, comma separated
     * ({@code README,ARCHITECTURE,ONBOARDING_GUIDE,API_REFERENCE}).
     *
     * <p>Stored as a plain string rather than a join table: the set is
     * small, it is written once and read as a whole, and it keeps the
     * job row readable in the database without an extra query.
     */
    @Column(name = "doc_types", length = 280)
    private String docTypes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RepositoryAnalysisJobStatus status = RepositoryAnalysisJobStatus.PENDING;

    /** How many files the repository scan found in total. */
    @Column(name = "total_files", nullable = false)
    private int totalFiles = 0;

    /** How many files the scan has walked so far. */
    @Column(name = "scanned_files", nullable = false)
    private int scannedFiles = 0;

    /** How many of the requested document types have been attempted. */
    @Column(name = "processed_documents", nullable = false)
    private int processedDocuments = 0;

    @Column(name = "generated_documents", nullable = false)
    private int generatedDocuments = 0;

    @Column(name = "failed_documents", nullable = false)
    private int failedDocuments = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * The per-document outcome: which documents were written, which
     * doc types failed and what went wrong for the failures.
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
    public String getDocTypes() { return docTypes; }
    public void setDocTypes(String docTypes) { this.docTypes = docTypes; }
    public RepositoryAnalysisJobStatus getStatus() { return status; }
    public void setStatus(RepositoryAnalysisJobStatus status) { this.status = status; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getScannedFiles() { return scannedFiles; }
    public void setScannedFiles(int scannedFiles) { this.scannedFiles = scannedFiles; }
    public int getProcessedDocuments() { return processedDocuments; }
    public void setProcessedDocuments(int processedDocuments) { this.processedDocuments = processedDocuments; }
    public int getGeneratedDocuments() { return generatedDocuments; }
    public void setGeneratedDocuments(int generatedDocuments) { this.generatedDocuments = generatedDocuments; }
    public int getFailedDocuments() { return failedDocuments; }
    public void setFailedDocuments(int failedDocuments) { this.failedDocuments = failedDocuments; }
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
