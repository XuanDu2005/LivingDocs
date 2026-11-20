package com.livingdocs.modules.drift.scan;

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
 * Tracks an asynchronous "scan a whole repository for documentation
 * drift" run.
 *
 * <p>The row is created synchronously when the user clicks
 * <em>Scan repository for drift</em> and the endpoint answers 202. A
 * background worker then mutates it in place: the status moves to
 * {@link RepositoryDriftScanStatus#RUNNING}, the counters advance as
 * each document is compared, and the job ends as
 * {@link RepositoryDriftScanStatus#SUCCEEDED},
 * {@link RepositoryDriftScanStatus#PARTIAL} or
 * {@link RepositoryDriftScanStatus#FAILED}.
 *
 * <p>The per-document outcome is kept in {@code resultJson} so a
 * partially failed run still shows the user which documents were
 * scanned cleanly and which ones could not be compared — a scan that
 * silently dropped half the repository would look like it succeeded.
 */
@Entity
@Table(
        name = "repository_drift_scan_jobs",
        indexes = {
                @Index(name = "idx_repo_drift_scan_jobs_workspace",
                        columnList = "workspace_id, created_at"),
                @Index(name = "idx_repo_drift_scan_jobs_repository",
                        columnList = "repository_id, created_at"),
                @Index(name = "idx_repo_drift_scan_jobs_status", columnList = "status")
        }
)
public class RepositoryDriftScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    /**
     * The pull request the scan is anchored to, if any.
     *
     * <p>With a pull request the "before" and "after" content of the
     * changed files is read from GitHub, so the comparison is exact.
     * Without one the scan compares the current code against the code
     * the documentation was last written against.
     */
    @Column(name = "pull_request_id")
    private UUID pullRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RepositoryDriftScanStatus status = RepositoryDriftScanStatus.PENDING;

    /** How many documents of the repository the scan covers. */
    @Column(name = "total_documents", nullable = false)
    private int totalDocuments = 0;

    /** How many of those documents have been compared so far. */
    @Column(name = "scanned_documents", nullable = false)
    private int scannedDocuments = 0;

    /** How many documents turned out to have drifted. */
    @Column(name = "documents_with_drift", nullable = false)
    private int documentsWithDrift = 0;

    /** How many drift alerts the scan filed in total. */
    @Column(name = "alerts_created", nullable = false)
    private int alertsCreated = 0;

    /** How many documents could not be compared. */
    @Column(name = "failed_documents", nullable = false)
    private int failedDocuments = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * The per-document outcome: which documents were scanned, how many
     * alerts each produced and what went wrong for the failures.
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
    public UUID getPullRequestId() { return pullRequestId; }
    public void setPullRequestId(UUID pullRequestId) { this.pullRequestId = pullRequestId; }
    public RepositoryDriftScanStatus getStatus() { return status; }
    public void setStatus(RepositoryDriftScanStatus status) { this.status = status; }
    public int getTotalDocuments() { return totalDocuments; }
    public void setTotalDocuments(int totalDocuments) { this.totalDocuments = totalDocuments; }
    public int getScannedDocuments() { return scannedDocuments; }
    public void setScannedDocuments(int scannedDocuments) { this.scannedDocuments = scannedDocuments; }
    public int getDocumentsWithDrift() { return documentsWithDrift; }
    public void setDocumentsWithDrift(int documentsWithDrift) { this.documentsWithDrift = documentsWithDrift; }
    public int getAlertsCreated() { return alertsCreated; }
    public void setAlertsCreated(int alertsCreated) { this.alertsCreated = alertsCreated; }
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
