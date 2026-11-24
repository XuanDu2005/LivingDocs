package com.livingdocs.modules.docsync.job;

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
 * Tracks an asynchronous "scan the repository's documentation and
 * queue update proposals" run.
 *
 * <p>The row is created synchronously when the user clicks
 * <em>Sync documentation</em> and the endpoint answers 202. A
 * background worker then mutates it in place: the status moves to
 * {@link DocSyncJobStatus#RUNNING}, the counters advance as each
 * drifted file is processed, and the job ends as
 * {@link DocSyncJobStatus#SUCCEEDED}, {@link DocSyncJobStatus#PARTIAL}
 * or {@link DocSyncJobStatus#FAILED}.
 *
 * <p>The per-file outcome is kept in {@code resultJson} so a
 * partially failed run still shows the user which files were
 * processed cleanly and which ones could not be — a sync that
 * silently dropped half the repository would look like it succeeded.
 */
@Entity
@Table(
        name = "repository_doc_sync_jobs",
        indexes = {
                @Index(name = "idx_repo_doc_sync_jobs_workspace",
                        columnList = "workspace_id, created_at"),
                @Index(name = "idx_repo_doc_sync_jobs_repository",
                        columnList = "repository_id, created_at"),
                @Index(name = "idx_repo_doc_sync_jobs_status", columnList = "status")
        }
)
public class DocSyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    /**
     * The branch the scan reads from, or {@code null} for the
     * repository's default branch.
     */
    @Column(name = "branch", length = 255)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DocSyncJobStatus status = DocSyncJobStatus.PENDING;

    /** How many Markdown files the repository scan found in total. */
    @Column(name = "total_files", nullable = false)
    private int totalFiles = 0;

    /** How many of those files have been processed so far. */
    @Column(name = "scanned_files", nullable = false)
    private int scannedFiles = 0;

    /** How many files turned out to have drifted. */
    @Column(name = "drifted_files", nullable = false)
    private int driftedFiles = 0;

    /** How many review proposals the run created. */
    @Column(name = "queued_proposals", nullable = false)
    private int queuedProposals = 0;

    /**
     * How many drifted files were <em>not</em> queued.
     *
     * <p>Either a proposal is already pending for that file, or the AI
     * proposed no real change against it.
     */
    @Column(name = "skipped_proposals", nullable = false)
    private int skippedProposals = 0;

    /** How many files could not be processed at all. */
    @Column(name = "failed_files", nullable = false)
    private int failedFiles = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * The per-file outcome: which files were scanned, how many
     * proposals each produced and what went wrong for the failures.
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
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public DocSyncJobStatus getStatus() { return status; }
    public void setStatus(DocSyncJobStatus status) { this.status = status; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getScannedFiles() { return scannedFiles; }
    public void setScannedFiles(int scannedFiles) { this.scannedFiles = scannedFiles; }
    public int getDriftedFiles() { return driftedFiles; }
    public void setDriftedFiles(int driftedFiles) { this.driftedFiles = driftedFiles; }
    public int getQueuedProposals() { return queuedProposals; }
    public void setQueuedProposals(int queuedProposals) { this.queuedProposals = queuedProposals; }
    public int getSkippedProposals() { return skippedProposals; }
    public void setSkippedProposals(int skippedProposals) { this.skippedProposals = skippedProposals; }
    public int getFailedFiles() { return failedFiles; }
    public void setFailedFiles(int failedFiles) { this.failedFiles = failedFiles; }
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
