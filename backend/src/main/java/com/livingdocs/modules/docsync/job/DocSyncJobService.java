package com.livingdocs.modules.docsync.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.docsync.job.DocSyncJobStatus;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.DocSyncService;
import com.livingdocs.modules.github.service.GithubCredentialService;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs a repository-wide documentation sync as a tracked job.
 *
 * <p>The "Sync documentation" button used to do this work inside a
 * single HTTP request: walk every Markdown file, ask the AI to
 * propose an update for every file that drifted, and queue a review
 * proposal for each proposal the AI produced.
 *
 * <p>That cannot work inside a request. Each drifted file costs one
 * LLM call of several seconds, so a repository with a handful of
 * drifted files needs minutes — far beyond any HTTP timeout. The
 * browser gave up and reported a timeout while the server kept
 * working and kept spending the LLM budget.
 *
 * <p>This service therefore does what the sibling repository-wide drift
 * scan already does: the controller creates a job row and answers 202,
 * and a background worker mutates the row in place so the UI can poll
 * it.
 */
@Service
public class DocSyncJobService {

    private static final Logger log = LoggerFactory.getLogger(DocSyncJobService.class);

    /**
     * How long a sync may run before it is considered abandoned.
     *
     * <p>A sync that has been running longer than this without
     * finishing belongs to a worker that died with the process.
     */
    static final long STALE_JOB_MINUTES = 30;

    private final DocSyncJobRepository jobRepository;
    private final RepositoryRepository repositoryRepository;
    private final DocSyncService docSyncService;
    private final GithubOAuthService githubOAuthService;
    private final GithubCredentialService credentialService;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    /**
     * Runs the job-row writes in their own transaction.
     *
     * <p>{@code @Transactional(REQUIRES_NEW)} cannot be used here: the
     * methods are called from {@link #run(UUID)} on the same bean, and
     * Spring's transaction proxy is bypassed by a self-invocation — the
     * propagation would silently never happen. A template does not rely
     * on the proxy at all.
     */
    private final TransactionTemplate tx;

    /**
     * The worker depends on this service, so the back-reference is
     * injected lazily. Without {@code @Lazy} the two beans form a
     * circular dependency, which Spring refuses to resolve.
     */
    private final DocSyncJobWorker worker;

    public DocSyncJobService(DocSyncJobRepository jobRepository,
                             RepositoryRepository repositoryRepository,
                             DocSyncService docSyncService,
                             GithubOAuthService githubOAuthService,
                             GithubCredentialService credentialService,
                             WorkspaceService workspaceService,
                             PermissionService permissionService,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager,
                             @Lazy DocSyncJobWorker worker) {
        this.jobRepository = jobRepository;
        this.repositoryRepository = repositoryRepository;
        this.docSyncService = docSyncService;
        this.githubOAuthService = githubOAuthService;
        this.credentialService = credentialService;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.worker = worker;
    }

    // -----------------------------------------------------------------
    // enqueue
    // -----------------------------------------------------------------

    /**
     * Enqueue a repository-wide documentation sync.
     *
     * <p>When a sync for the same repository is already in flight, that
     * job is returned instead of starting a second one — two concurrent
     * syncs would create duplicate proposals and burn the LLM budget
     * twice.
     *
     * @param branch the branch to scan, or {@code null} for the default
     *               branch
     */
    @Transactional
    public DocSyncJob enqueue(UUID actorId, UUID workspaceId, UUID repositoryId, String branch) {
        workspaceService.requireMember(actorId, workspaceId);
        // Scanning a repository costs one LLM call per drifted file, so
        // it is a capability rather than a plain member action.
        permissionService.requireAnyPermission(actorId, PermissionCodes.DRIFT_DETECT_RUN);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Repository not found in this workspace");
        }

        List<DocSyncJob> active = jobRepository.findActiveForRepository(repositoryId);
        if (!active.isEmpty()) {
            // A job left over from a previous run of the service has no
            // worker behind it any more: the thread that was executing
            // it died with the process. Such a job must not be handed
            // back as if it were running — it would never finish.
            active = active.stream()
                    .filter(j -> !isStale(j))
                    .toList();
            if (!active.isEmpty()) {
                log.info("Documentation sync already running for repository {} — reusing job {}",
                        repositoryId, active.get(0).getId());
                return active.get(0);
            }
            closeStaleJobs(repositoryId);
        }

        DocSyncJob job = new DocSyncJob();
        job.setWorkspaceId(workspaceId);
        job.setRepositoryId(repositoryId);
        job.setBranch(branch);
        job.setRequestedBy(actorId);
        job.setStatus(DocSyncJobStatus.PENDING);
        job.setCreatedAt(OffsetDateTime.now());
        DocSyncJob saved = jobRepository.save(job);

        // Hand the job over to the background worker *after* the
        // transaction commits, so the row is visible to the worker when
        // it starts.
        startAfterCommit(saved.getId());

        return saved;
    }

    /**
     * Whether a job has been left behind by a dead worker.
     */
    private boolean isStale(DocSyncJob job) {
        OffsetDateTime since = job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt();
        if (since == null) {
            return true;
        }
        return since.isBefore(OffsetDateTime.now().minusMinutes(STALE_JOB_MINUTES));
    }

    /**
     * Close the jobs of a repository that no worker will ever finish.
     */
    private void closeStaleJobs(UUID repositoryId) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(STALE_JOB_MINUTES);
        List<DocSyncJob> stale = jobRepository.findStaleBefore(cutoff).stream()
                .filter(j -> j.getRepositoryId().equals(repositoryId))
                .toList();
        if (stale.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            for (DocSyncJob job : stale) {
                DocSyncJob current = jobRepository.findById(job.getId()).orElse(null);
                if (current == null) {
                    continue;
                }
                if (current.getStatus() != DocSyncJobStatus.PENDING
                        && current.getStatus() != DocSyncJobStatus.RUNNING) {
                    continue;
                }
                current.setStatus(DocSyncJobStatus.FAILED);
                current.setErrorMessage(
                        "The sync was interrupted — the service restarted while it was running.");
                current.setFinishedAt(OffsetDateTime.now());
                jobRepository.save(current);
                log.info("Closed stale documentation sync job {}", current.getId());
            }
        });
    }

    private void startAfterCommit(UUID jobId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.run(jobId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.run(jobId);
            }
        });
    }

    // -----------------------------------------------------------------
    // execution
    // -----------------------------------------------------------------

    /**
     * Run the job, catching everything.
     *
     * <p>Called by {@link DocSyncJobWorker} on the background
     * executor. The catch-all guarantees the job never stays in
     * {@code RUNNING} forever: a crashed run has to say so.
     */
    public void runJob(UUID jobId) {
        try {
            run(jobId);
        } catch (Exception ex) {
            log.error("Documentation sync job {} crashed: {}", jobId, ex.getMessage(), ex);
            try {
                markFailed(jobId, ex.getMessage());
            } catch (Exception ignore) {
                // Last-resort failure marker; never throw further.
            }
        }
    }

    /**
     * Run the sync.
     *
     * <p>Deliberately <b>not</b> transactional. The run makes one slow
     * LLM call per drifted file, and holding one transaction open across
     * them would keep a database connection busy for the whole run.
     * Every step that writes manages its own transaction.
     */
    public void run(UUID jobId) {
        DocSyncJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() != DocSyncJobStatus.PENDING) return;

        markRunning(jobId);

        Repository repo = repositoryRepository.findById(job.getRepositoryId()).orElse(null);
        if (repo == null) {
            markFailed(jobId, "Repository " + job.getRepositoryId() + " no longer exists");
            return;
        }

        // The sync needs a GitHub credential. Without one it cannot see
        // the repository at all, so it fails early with a message the
        // user can act on.
        String token = resolveAccessToken(repo, job.getRequestedBy());
        if (token == null || token.isBlank()) {
            markFailed(jobId, "No GitHub connection is available for this repository. "
                    + "Re-authorize the GitHub app and try again.");
            return;
        }

        // The scan itself is cheap — one GitHub listing call per
        // directory — and tells us which files drifted.
        DocSyncService.ScanResult scan = docSyncService.scanRepository(
                job.getRequestedBy(), repo.getWorkspaceId(), repo.getId(), token, job.getBranch());

        List<DocSyncService.DocFileInfo> driftedFiles = scan.files().stream()
                .filter(DocSyncService.DocFileInfo::hasDrift)
                .toList();

        updateProgress(jobId, scan.totalFiles(), 0, driftedFiles.size(), 0, 0);

        List<Map<String, Object>> results = new ArrayList<>();
        int processed = 0;
        int queued = 0;
        int skipped = 0;
        int failed = 0;

        for (DocSyncService.DocFileInfo file : driftedFiles) {
            DocSyncService.FileProposalOutcome outcome = docSyncService.queueProposalForFile(
                    job.getRequestedBy(), repo.getWorkspaceId(), repo.getId(),
                    token, job.getBranch(), file.path());
            processed++;
            if (outcome.queued()) {
                queued++;
            } else if (outcome.skipped()) {
                skipped++;
            } else {
                failed++;
            }
            results.add(toRow(file.path(), outcome));
            updateProgress(jobId, scan.totalFiles(), processed, driftedFiles.size(), queued, skipped);
        }

        finish(jobId, scan.totalFiles(), processed, driftedFiles.size(),
                queued, skipped, failed, results);
    }

    /**
     * The GitHub credential to read the repository with.
     *
     * <p>The credential of the user who connected the repository is
     * preferred — that is the one whose scopes cover the repository.
     * The requesting user is the fallback.
     *
     * <p>Neither lookup goes through {@code CurrentUser}: the sync
     * runs on a background thread, where there is no authenticated
     * request to read the current user from.
     */
    /**
     * The GitHub credential to read the repository with.
     *
     * <p>The credential of the user who connected the repository is
     * preferred, the requesting user comes next, and every other member
     * of the owning workspace is the last resort. Each candidate is
     * validated against GitHub before it is used, so one expired token
     * does not fail the whole sync — and a connection that GitHub has
     * rejected is marked as expired instead of being retried forever.
     */
    private String resolveAccessToken(Repository repo, UUID requestedBy) {
        return credentialService.resolveTokenForRepository(repo, requestedBy);
    }

    // -----------------------------------------------------------------
    // queries
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<DocSyncJob> find(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    @Transactional(readOnly = true)
    public List<DocSyncJob> recentForWorkspace(UUID workspaceId) {
        return jobRepository.findTop20ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * Render a job as the response body the frontend polls.
     *
     * <p>The stored {@code resultJson} carries the per-file outcome; it
     * is decoded here so the client does not have to parse a nested
     * string.
     */
    public Map<String, Object> toResponse(DocSyncJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getId().toString());
        out.put("status", job.getStatus().name());
        out.put("repositoryId", job.getRepositoryId().toString());
        out.put("branch", job.getBranch());
        out.put("totalFiles", job.getTotalFiles());
        out.put("scannedFiles", job.getScannedFiles());
        out.put("driftedFiles", job.getDriftedFiles());
        out.put("queuedProposals", job.getQueuedProposals());
        out.put("skippedProposals", job.getSkippedProposals());
        out.put("failedFiles", job.getFailedFiles());
        out.put("errorMessage", job.getErrorMessage());
        out.put("createdAt", job.getCreatedAt());
        out.put("startedAt", job.getStartedAt());
        out.put("finishedAt", job.getFinishedAt());

        if (job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                out.putAll(objectMapper.readValue(job.getResultJson(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Could not decode result of documentation sync job {}: {}",
                        job.getId(), e.getMessage());
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------

    private void markRunning(UUID jobId) {
        tx.executeWithoutResult(status -> {
            DocSyncJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setStatus(DocSyncJobStatus.RUNNING);
            job.setStartedAt(OffsetDateTime.now());
            jobRepository.save(job);
        });
    }

    /**
     * Persist the running counters. Runs in its own transaction so the
     * progress survives a rollback of the enclosing work.
     */
    private void updateProgress(UUID jobId, int total, int processed,
                                int drifted, int queued, int skipped) {
        tx.executeWithoutResult(status -> {
            DocSyncJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setTotalFiles(total);
            job.setScannedFiles(processed);
            job.setDriftedFiles(drifted);
            job.setQueuedProposals(queued);
            job.setSkippedProposals(skipped);
            jobRepository.save(job);
        });
    }

    private void finish(UUID jobId, int total, int processed, int drifted,
                        int queued, int skipped, int failed,
                        List<Map<String, Object>> results) {
        tx.executeWithoutResult(status -> {
            DocSyncJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;

            job.setTotalFiles(total);
            job.setScannedFiles(processed);
            job.setDriftedFiles(drifted);
            job.setQueuedProposals(queued);
            job.setSkippedProposals(skipped);
            job.setFailedFiles(failed);
            job.setFinishedAt(OffsetDateTime.now());

            // PARTIAL is distinct from SUCCEEDED: the user needs to
            // know that some files could not be processed, otherwise a
            // run that covered 5 of 6 drifted files would look like a
            // complete answer.
            if (failed == 0) {
                job.setStatus(DocSyncJobStatus.SUCCEEDED);
            } else if (processed > failed) {
                job.setStatus(DocSyncJobStatus.PARTIAL);
            } else {
                job.setStatus(DocSyncJobStatus.FAILED);
                job.setErrorMessage("No file could be processed.");
            }

            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("queuedProposals", queued);
                payload.put("skippedProposals", skipped);
                payload.put("files", results);
                job.setResultJson(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("Could not serialise documentation sync result for job {}: {}",
                        jobId, e.getMessage());
            }

            jobRepository.save(job);
            log.info("Documentation sync job {} finished: status={}, files={}, drifted={}, queued={}, skipped={}",
                    jobId, job.getStatus(), processed, drifted, queued, skipped);
        });
    }

    private void markFailed(UUID jobId, String message) {
        tx.executeWithoutResult(status -> {
            DocSyncJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setStatus(DocSyncJobStatus.FAILED);
            job.setErrorMessage(message);
            job.setFinishedAt(OffsetDateTime.now());
            jobRepository.save(job);
        });
    }

    /**
     * The outcome of processing one drifted file.
     */
    private Map<String, Object> toRow(String path, DocSyncService.FileProposalOutcome outcome) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("status", outcome.queued() ? "QUEUED"
                : (outcome.skipped() ? "SKIPPED" : "FAILED"));
        if (outcome.message() != null) {
            row.put("message", outcome.message());
        }
        return row;
    }
}
