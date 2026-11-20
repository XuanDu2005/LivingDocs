package com.livingdocs.modules.drift.scan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
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
 * Scans a whole repository for documentation drift.
 *
 * <p>The document-scoped drift flow answers "does <em>this</em>
 * document still match the code?". That is the wrong question when the
 * documentation has drifted: the user does not know which document to
 * ask about. This service answers the useful question instead — "which
 * documents of this repository no longer match the code?" — by walking
 * every document of the repository and comparing each one.
 *
 * <p>The work costs one LLM call per document, so it cannot run inside
 * an HTTP request: a repository with a dozen documents would take
 * minutes. The controller therefore enqueues a job and answers 202; the
 * worker mutates the job row in place so the UI can poll it.
 */
@Service
public class RepositoryDriftScanService {

    private static final Logger log =
            LoggerFactory.getLogger(RepositoryDriftScanService.class);

    /**
     * How long a scan may run before it is considered abandoned.
     *
     * <p>A scan that has been running longer than this without
     * finishing belongs to a worker that died with the process.
     */
    static final long STALE_JOB_MINUTES = 30;

    private final RepositoryDriftScanJobRepository jobRepository;
    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final DocumentRepository documentRepository;
    private final DriftSourceResolver sourceResolver;
    private final AiDocumentationService aiDocumentationService;
    private final GithubOAuthService githubOAuthService;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final WorkspaceMemberRepository memberRepository;
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
    private final RepositoryDriftScanWorker worker;

    public RepositoryDriftScanService(RepositoryDriftScanJobRepository jobRepository,
                                      RepositoryRepository repositoryRepository,
                                      PullRequestRepository pullRequestRepository,
                                      DocumentRepository documentRepository,
                                      DriftSourceResolver sourceResolver,
                                      AiDocumentationService aiDocumentationService,
                                      GithubOAuthService githubOAuthService,
                                      WorkspaceService workspaceService,
                                      PermissionService permissionService,
                                      WorkspaceMemberRepository memberRepository,
                                      ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager,
                                      @Lazy RepositoryDriftScanWorker worker) {
        this.jobRepository = jobRepository;
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.documentRepository = documentRepository;
        this.sourceResolver = sourceResolver;
        this.aiDocumentationService = aiDocumentationService;
        this.githubOAuthService = githubOAuthService;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.worker = worker;
    }

    // -----------------------------------------------------------------
    // enqueue
    // -----------------------------------------------------------------

    /**
     * Enqueue a repository-wide drift scan.
     *
     * <p>When a scan for the same repository is already in flight, that
     * job is returned instead of starting a second one — two concurrent
     * scans would file duplicate drift alerts and burn the LLM budget
     * twice.
     *
     * @param pullRequestId the pull request to anchor the comparison to,
     *                      or {@code null} to compare against the
     *                      current head of the default branch
     */
    @Transactional
    public RepositoryDriftScanJob enqueue(UUID actorId,
                                           UUID workspaceId,
                                           UUID repositoryId,
                                           UUID pullRequestId) {
        workspaceService.requireMember(actorId, workspaceId);
        // Scanning a repository costs one LLM call per document, so it
        // is a capability rather than a plain member action.
        permissionService.requireAnyPermission(actorId, PermissionCodes.DRIFT_DETECT_RUN);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Repository not found in this workspace");
        }

        if (pullRequestId != null) {
            PullRequest pr = pullRequestRepository.findById(pullRequestId)
                    .orElseThrow(() -> new IllegalArgumentException("Pull request not found"));
            if (!pr.getRepositoryId().equals(repositoryId)) {
                throw new IllegalArgumentException(
                        "Pull request does not belong to this repository");
            }
        }

        List<RepositoryDriftScanJob> active = jobRepository.findActiveForRepository(repositoryId);
        if (!active.isEmpty()) {
            // A job left over from a previous run of the service has no
            // worker behind it any more: the thread that was executing
            // it died with the process. Such a job must not be handed
            // back as if it were running — it would never finish.
            active = active.stream()
                    .filter(j -> !isStale(j))
                    .toList();
            if (!active.isEmpty()) {
                log.info("Repository drift scan already running for repository {} — reusing job {}",
                        repositoryId, active.get(0).getId());
                return active.get(0);
            }
            closeStaleJobs(repositoryId);
        }

        RepositoryDriftScanJob job = new RepositoryDriftScanJob();
        job.setWorkspaceId(workspaceId);
        job.setRepositoryId(repositoryId);
        job.setPullRequestId(pullRequestId);
        job.setRequestedBy(actorId);
        job.setStatus(RepositoryDriftScanStatus.PENDING);
        job.setCreatedAt(OffsetDateTime.now());
        RepositoryDriftScanJob saved = jobRepository.save(job);

        // Hand the job over to the background worker *after* the
        // transaction commits, so the row is visible to the worker when
        // it starts.
        startAfterCommit(saved.getId());

        return saved;
    }

    /**
     * Whether a job has been left behind by a dead worker.
     *
     * <p>A scan that started more than {@link #STALE_JOB_MINUTES}
     * minutes ago and has neither advanced nor finished belongs to a
     * process that no longer exists.
     */
    private boolean isStale(RepositoryDriftScanJob job) {
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
        List<RepositoryDriftScanJob> stale = jobRepository.findStaleBefore(cutoff).stream()
                .filter(j -> j.getRepositoryId().equals(repositoryId))
                .toList();
        if (stale.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            for (RepositoryDriftScanJob job : stale) {
                RepositoryDriftScanJob current = jobRepository.findById(job.getId()).orElse(null);
                if (current == null) {
                    continue;
                }
                if (current.getStatus() != RepositoryDriftScanStatus.PENDING
                        && current.getStatus() != RepositoryDriftScanStatus.RUNNING) {
                    continue;
                }
                current.setStatus(RepositoryDriftScanStatus.FAILED);
                current.setErrorMessage(
                        "The scan was interrupted — the service restarted while it was running.");
                current.setFinishedAt(OffsetDateTime.now());
                jobRepository.save(current);
                log.info("Closed stale repository drift scan job {}", current.getId());
            }
        });
    }

    private void startAfterCommit(UUID jobId) {        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
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
     * Run the scan, catching everything.
     *
     * <p>Called by {@link RepositoryDriftScanWorker} on the background
     * executor. The catch-all guarantees the job never stays in
     * {@code RUNNING} forever: a crashed run has to say so.
     */
    public void runJob(UUID jobId) {
        try {
            run(jobId);
        } catch (Exception ex) {
            log.error("Repository drift scan job {} crashed: {}", jobId, ex.getMessage(), ex);
            try {
                markFailed(jobId, ex.getMessage());
            } catch (Exception ignore) {
                // Last-resort failure marker; never throw further.
            }
        }
    }

    /**
     * Run the scan.
     *
     * <p>Deliberately <b>not</b> transactional. The run makes one slow
     * LLM call per document, and holding one transaction open across
     * them would keep a database connection busy for the whole run.
     * Every step that writes manages its own transaction.
     */
    public void run(UUID jobId) {
        RepositoryDriftScanJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() != RepositoryDriftScanStatus.PENDING) return;

        markRunning(jobId);

        Repository repo = repositoryRepository.findById(job.getRepositoryId()).orElse(null);
        if (repo == null) {
            markFailed(jobId, "Repository " + job.getRepositoryId() + " no longer exists");
            return;
        }

        // The scan needs a GitHub credential. Without one it cannot see
        // the repository at all, so it fails early with a message the
        // user can act on.
        String token = resolveAccessToken(repo, job.getRequestedBy());
        if (token == null || token.isBlank()) {
            markFailed(jobId, "No GitHub connection is available for this repository. "
                    + "Re-authorize the GitHub app and try again.");
            return;
        }

        PullRequest pr = job.getPullRequestId() == null
                ? null
                : pullRequestRepository.findById(job.getPullRequestId()).orElse(null);

        List<Document> documents = scanableDocuments(repo);
        updateProgress(jobId, documents.size(), 0, 0, 0);

        List<Map<String, Object>> results = new ArrayList<>();
        int scanned = 0;
        int withDrift = 0;
        int alerts = 0;
        int failed = 0;

        for (Document doc : documents) {
            DocumentOutcome outcome = _scanDocument(job, repo, token, doc, pr);
            scanned++;
            if (!outcome.success()) {
                failed++;
            } else if (outcome.alerts() > 0) {
                withDrift++;
                alerts += outcome.alerts();
            }
            results.add(outcome.toRow());
            updateProgress(jobId, documents.size(), scanned, withDrift, alerts);
        }

        finish(jobId, scanned, withDrift, alerts, failed, results);
    }
    /**
     * Compare one document against the code and file the alerts.
     */
    private DocumentOutcome _scanDocument(RepositoryDriftScanJob job,
                                          Repository repo,
                                          String token,
                                          Document doc,
                                          PullRequest pr) {
        try {
            DriftSourceResolver.Comparison comparison =
                    sourceResolver.resolve(repo, token, doc, pr);
            if (comparison.isSkipped()) {
                return DocumentOutcome.skipped(doc, comparison.skipped());
            }

            int filed = aiDocumentationService.detectDriftForPullRequest(
                    job.getRequestedBy(),
                    repo.getWorkspaceId(),
                    repo.getId(),
                    pr == null ? null : pr.getId(),
                    doc.getId(),
                    comparison.filesBefore(),
                    comparison.filesAfter());
            if (filed > 0) {
                log.info("Repository drift scan filed {} alert(s) for document {} in {}",
                        filed, doc.getTitle(), repo.getFullName());
            }
            return DocumentOutcome.success(doc, filed, comparison.beforeRef(),
                    comparison.afterRef());
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException ex) {
            // The credential is expired. Every document of this
            // repository would fail the same way, so the run is stopped
            // at once with a message the user can act on.
            log.warn("GitHub credential is no longer valid for repository {}: {}",
                    repo.getFullName(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            // A document that cannot be compared must not abort the
            // scan — the other documents are still worth checking.
            log.warn("Drift comparison failed for document {}: {}", doc.getTitle(), ex.getMessage());
            return DocumentOutcome.failure(doc, ex.getMessage());
        }
    }

    /**
     * The documents the scan covers.
     *
     * <p>Archived documents are left out: they are no longer
     * maintained, so drift in them is not actionable. Drafts are
     * included — a draft that has already drifted is exactly the
     * document the author should not publish as it is.
     */
    private List<Document> scanableDocuments(Repository repo) {
        List<Document> all = documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repo.getId());
        List<Document> out = new ArrayList<>();
        for (Document doc : all) {
            if (doc.getStatus() == DocumentStatus.ARCHIVED) {
                continue;
            }
            out.add(doc);
        }
        return out;
    }

    /**
     * The GitHub credential to read the repository with.
     *
     * <p>The credential of the user who connected the repository is
     * preferred — that is the one whose scopes cover the repository.
     * The requesting user is the fallback.
     *
     * <p>Neither lookup goes through {@code CurrentUser}: the scan
     * runs on a background thread, where there is no authenticated
     * request to read the current user from.
     */
    private String resolveAccessToken(Repository repo, UUID requestedBy) {
        Optional<com.livingdocs.modules.github.model.GithubConnection> connection =
                githubOAuthService.findForUser(repo.getConnectedBy());
        if (connection.isPresent()) {
            String token = connection.get().getAccessToken();
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return githubOAuthService.findForUser(requestedBy)
                .map(c -> c.getAccessToken())
                .orElse(null);
    }

    // -----------------------------------------------------------------
    // queries
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<RepositoryDriftScanJob> find(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    @Transactional(readOnly = true)
    public List<RepositoryDriftScanJob> recentForWorkspace(UUID workspaceId) {
        return jobRepository.findTop20ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * Render a job as the response body the frontend polls.
     *
     * <p>The stored {@code resultJson} carries the per-document
     * outcome; it is decoded here so the client does not have to parse
     * a nested string.
     */
    public Map<String, Object> toResponse(RepositoryDriftScanJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getId().toString());
        out.put("status", job.getStatus().name());
        out.put("repositoryId", job.getRepositoryId().toString());
        out.put("pullRequestId", job.getPullRequestId());
        out.put("totalDocuments", job.getTotalDocuments());
        out.put("scannedDocuments", job.getScannedDocuments());
        out.put("documentsWithDrift", job.getDocumentsWithDrift());
        out.put("alertsCreated", job.getAlertsCreated());
        out.put("failedDocuments", job.getFailedDocuments());
        out.put("errorMessage", job.getErrorMessage());
        out.put("createdAt", job.getCreatedAt());
        out.put("startedAt", job.getStartedAt());
        out.put("finishedAt", job.getFinishedAt());

        if (job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                out.putAll(objectMapper.readValue(job.getResultJson(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Could not decode result of repository drift scan job {}: {}",
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
            RepositoryDriftScanJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setStatus(RepositoryDriftScanStatus.RUNNING);
            job.setStartedAt(OffsetDateTime.now());
            jobRepository.save(job);
        });
    }

    /**
     * Persist the running counters. Runs in its own transaction so the
     * progress survives a rollback of the enclosing work.
     */
    private void updateProgress(UUID jobId, int total, int scanned,
                                 int withDrift, int alerts) {
        tx.executeWithoutResult(status -> {
            RepositoryDriftScanJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setTotalDocuments(total);
            job.setScannedDocuments(scanned);
            job.setDocumentsWithDrift(withDrift);
            job.setAlertsCreated(alerts);
            jobRepository.save(job);
        });
    }

    private void finish(UUID jobId, int scanned, int withDrift, int alerts,
                        int failed, List<Map<String, Object>> results) {
        tx.executeWithoutResult(status -> {
            RepositoryDriftScanJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;

            job.setScannedDocuments(scanned);
            job.setDocumentsWithDrift(withDrift);
            job.setAlertsCreated(alerts);
            job.setFailedDocuments(failed);
            job.setFinishedAt(OffsetDateTime.now());

            // PARTIAL is distinct from SUCCEEDED: the user needs to
            // know that some documents could not be compared, otherwise
            // a run that covered 12 of 15 documents would look like a
            // complete answer.
            if (failed == 0) {
                job.setStatus(RepositoryDriftScanStatus.SUCCEEDED);
            } else if (scanned > failed) {
                job.setStatus(RepositoryDriftScanStatus.PARTIAL);
            } else {
                job.setStatus(RepositoryDriftScanStatus.FAILED);
                job.setErrorMessage("No document could be compared.");
            }

            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("alertsCreated", alerts);
                payload.put("documentsWithDrift", withDrift);
                payload.put("documents", results);
                job.setResultJson(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("Could not serialise repository drift scan result for job {}: {}",
                        jobId, e.getMessage());
            }

            jobRepository.save(job);
            log.info("Repository drift scan job {} finished: status={}, scanned={}, drifted={}, alerts={}",
                    jobId, job.getStatus(), scanned, withDrift, alerts);
        });
    }

    private void markFailed(UUID jobId, String message) {
        tx.executeWithoutResult(status -> {
            RepositoryDriftScanJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setStatus(RepositoryDriftScanStatus.FAILED);
            job.setErrorMessage(message);
            job.setFinishedAt(OffsetDateTime.now());
            jobRepository.save(job);
        });
    }

    /**
     * The outcome of comparing one document.
     */
    private record DocumentOutcome(boolean success,
                                   UUID documentId,
                                   String title,
                                   int alerts,
                                   String beforeRef,
                                   String afterRef,
                                   String reason,
                                   String errorMessage) {

        static DocumentOutcome success(Document doc, int alerts,
                                        String beforeRef, String afterRef) {
            return new DocumentOutcome(true, doc.getId(), doc.getTitle(), alerts,
                    beforeRef, afterRef, null, null);
        }

        static DocumentOutcome skipped(Document doc, String reason) {
            return new DocumentOutcome(true, doc.getId(), doc.getTitle(), 0,
                    null, null, reason, null);
        }

        static DocumentOutcome failure(Document doc, String message) {
            return new DocumentOutcome(false, doc.getId(), doc.getTitle(), 0,
                    null, null, null, message);
        }

        Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentId", documentId.toString());
            row.put("title", title);
            row.put("status", errorMessage != null ? "FAILED"
                    : (reason != null ? "SKIPPED" : "SCANNED"));
            row.put("alerts", alerts);
            if (beforeRef != null) row.put("beforeRef", beforeRef);
            if (afterRef != null) row.put("afterRef", afterRef);
            if (reason != null) row.put("reason", reason);
            if (errorMessage != null) row.put("error", errorMessage);
            return row;
        }
    }
}
