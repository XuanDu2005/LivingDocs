package com.livingdocs.modules.ai.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.document.dto.CreateDocumentRequest;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.document.service.DocumentService;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.template.service.TemplateSchemaResolver;
import com.livingdocs.modules.version.service.DocumentVersionService;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the repository-level analysis in the background.
 *
 * <p>Unlike the bulk entity generation, this flow does not need the
 * repository to have been indexed first: it reads the repository
 * straight from GitHub. What it produces is a small set of
 * <em>overview</em> documents — a README, an architecture guide, an
 * onboarding guide and an API reference — each grounded on the
 * repository as a whole.
 *
 * <p>The work takes a while: the scan walks the repository tree and
 * every document type costs one LLM call. The controller therefore
 * enqueues a job and answers 202; the worker mutates the job row in
 * place so the UI can poll it.
 */
@Service
public class RepositoryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryAnalysisService.class);

    /**
     * The document types a repository analysis produces.
     *
     * <p>These are the four overview documents a repository actually
     * needs: what it is, how it is put together, how to work on it,
     * and what it exposes.
     */
    public static final List<String> DEFAULT_DOC_TYPES = List.of(
            "README", "ARCHITECTURE", "ONBOARDING_GUIDE", "API_REFERENCE");

    private final RepositoryAnalysisJobRepository jobRepository;
    private final RepositoryRepository repositoryRepository;
    private final RepositorySnapshotScanner snapshotScanner;
    private final AiServiceClient aiClient;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final DocumentVersionService versionService;
    private final TemplateSchemaResolver schemaResolver;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final GithubOAuthService githubOAuthService;
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
    private final RepositoryAnalysisWorker worker;

    public RepositoryAnalysisService(RepositoryAnalysisJobRepository jobRepository,
                                     RepositoryRepository repositoryRepository,
                                     RepositorySnapshotScanner snapshotScanner,
                                     AiServiceClient aiClient,
                                     DocumentRepository documentRepository,
                                     DocumentService documentService,
                                     DocumentVersionService versionService,
                                     TemplateSchemaResolver schemaResolver,
                                     WorkspaceService workspaceService,
                                     PermissionService permissionService,
                                     GithubOAuthService githubOAuthService,
                                     ObjectMapper objectMapper,
                                     PlatformTransactionManager transactionManager,
                                     @Lazy RepositoryAnalysisWorker worker) {
        this.jobRepository = jobRepository;
        this.repositoryRepository = repositoryRepository;
        this.snapshotScanner = snapshotScanner;
        this.aiClient = aiClient;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.versionService = versionService;
        this.schemaResolver = schemaResolver;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.githubOAuthService = githubOAuthService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.worker = worker;
    }

    // -----------------------------------------------------------------
    // enqueue
    // -----------------------------------------------------------------

    /**
     * Enqueue a repository analysis run.
     *
     * <p>When a run for the same repository is already in flight, that
     * job is returned instead of starting a second one — two concurrent
     * runs would race on the same overview documents and burn the LLM
     * budget twice.
     */
    @Transactional
    public RepositoryAnalysisJob enqueue(UUID actorId, UUID workspaceId,
                                         UUID repositoryId, List<String> docTypes) {
        workspaceService.requireMember(actorId, workspaceId);
        // Analysing a repository costs LLM tokens, so it is a capability
        // rather than a plain member action.
        permissionService.requireAnyPermission(actorId, PermissionCodes.AI_RUN_GENERATION);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Repository not found in this workspace");
        }

        List<RepositoryAnalysisJob> active = jobRepository.findActiveForRepository(repositoryId);
        if (!active.isEmpty()) {
            log.info("Repository analysis already running for repository {} — reusing job {}",
                    repositoryId, active.get(0).getId());
            return active.get(0);
        }

        List<String> effectiveTypes = _normaliseDocTypes(docTypes);

        RepositoryAnalysisJob job = new RepositoryAnalysisJob();
        job.setWorkspaceId(workspaceId);
        job.setRepositoryId(repositoryId);
        job.setDocTypes(String.join(",", effectiveTypes));
        job.setRequestedBy(actorId);
        job.setStatus(RepositoryAnalysisJobStatus.PENDING);
        job.setCreatedAt(OffsetDateTime.now());
        RepositoryAnalysisJob saved = jobRepository.save(job);

        // Hand the job over to the background worker *after* the
        // transaction commits, so the row is visible to the worker when
        // it starts.
        startAfterCommit(saved.getId());

        return saved;
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
     * Run the analysis, catching everything.
     *
     * <p>Called by {@link RepositoryAnalysisWorker} on the background
     * executor. The catch-all guarantees the job never stays in
     * {@code RUNNING} forever: a crashed run has to say so.
     */
    public void runJob(UUID jobId) {
        try {
            run(jobId);
        } catch (Exception ex) {
            log.error("Repository analysis job {} crashed: {}", jobId, ex.getMessage(), ex);
            try {
                markFailed(jobId, ex.getMessage());
            } catch (Exception ignore) {
                // Last-resort failure marker; never throw further.
            }
        }
    }

    /**
     * Run the analysis.
     *
     * <p>Deliberately <b>not</b> transactional. The run makes several
     * slow LLM calls, and holding one transaction open across them
     * would keep a database connection busy for the whole run. Every
     * step that writes (the job row, the documents, the versions)
     * manages its own transaction.
     */
    public void run(UUID jobId) {
        RepositoryAnalysisJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() != RepositoryAnalysisJobStatus.PENDING) return;

        markRunning(jobId);

        Repository repo = repositoryRepository.findById(job.getRepositoryId()).orElse(null);
        if (repo == null) {
            markFailed(jobId, "Repository " + job.getRepositoryId() + " no longer exists");
            return;
        }

        List<String> docTypes = _splitDocTypes(job.getDocTypes());

        // The scan needs a GitHub credential. Without one the run
        // cannot see the repository at all, so it fails early with a
        // message the user can act on.
        String token = _resolveAccessToken(repo, job.getRequestedBy());
        if (token == null || token.isBlank()) {
            markFailed(jobId, "No GitHub connection is available for this repository. "
                    + "Re-authorize the GitHub app and try again.");
            return;
        }

        RepositorySnapshot snapshot = snapshotScanner.scan(repo, token);
        updateScanProgress(jobId, snapshot.totalFiles(), snapshot.totalFiles());

        List<Map<String, Object>> documents = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int generated = 0;

        for (String docType : docTypes) {
            try {
                DocumentOutcome outcome = _generateDocument(job, repo, snapshot, docType);
                if (outcome.success()) {
                    generated++;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("docType", docType);
                    row.put("documentId", outcome.documentId().toString());
                    row.put("title", outcome.title());
                    row.put("slug", outcome.slug());
                    row.put("versionNumber", outcome.versionNumber());
                    documents.add(row);
                } else {
                    errors.add(docType + ": " + outcome.errorMessage());
                }
            } catch (Exception e) {
                log.error("Failed to generate {} for repository {}: {}",
                        docType, repo.getFullName(), e.getMessage());
                errors.add(docType + ": " + e.getMessage());
            }
            updateProgress(jobId, generated, errors.size());
        }

        finish(jobId, generated, errors, documents);
    }

    /**
     * Generate one overview document and persist it.
     */
    private DocumentOutcome _generateDocument(RepositoryAnalysisJob job,
                                              Repository repo,
                                              RepositorySnapshot snapshot,
                                              String docType) {
        // The workspace's own template for this doc type drives the
        // structure; without one the AI service falls back to its
        // built-in preset for the doc type.
        String schema = schemaResolver.resolveForDocType(job.getWorkspaceId(), docType);

        AiDtos.GenerateResponse resp = aiClient.generateRepository(
                job.getWorkspaceId(),
                _toWireSnapshot(snapshot),
                docType,
                schema,
                "Write a '" + docType + "' document for the whole repository "
                        + repo.getFullName() + ". Ground every statement in the snapshot.");
        if (resp == null || resp.markdown() == null || resp.markdown().isBlank()) {
            return DocumentOutcome.failure("AI service unavailable for " + docType);
        }

        String title = _titleFor(repo, docType);
        String slug = _slugFor(repo, docType);
        float confidence = (float) Math.max(0.0, Math.min(1.0, resp.confidence()));

        // A re-run of the analysis should update the existing overview
        // document rather than fail on a duplicate slug — the document
        // type is the same, only the repository moved on.
        var existing = documentRepository.findByWorkspaceIdAndSlug(job.getWorkspaceId(), slug);
        UUID documentId;
        if (existing.isPresent()) {
            documentId = existing.get().getId();
        } else {
            var createReq = new CreateDocumentRequest(
                    repo.getId(), null, title, slug, docType, null, false, resp.markdown());
            documentId = documentService.create(
                    job.getRequestedBy(), job.getWorkspaceId(), createReq).id();
        }

        var version = versionService.createAiVersionInternal(
                job.getRequestedBy(),
                documentId,
                resp.markdown(),
                "AI repository analysis for " + repo.getFullName(),
                null, null, confidence, _sourcesToJson(resp));

        return DocumentOutcome.success(documentId, title, slug, version.getVersionNumber());
    }

    /**
     * The title of an overview document.
     */
    private static String _titleFor(Repository repo, String docType) {
        return switch (docType) {
            case "README" -> repo.getName() + " — README";
            case "ARCHITECTURE" -> repo.getName() + " — Architecture";
            case "ONBOARDING_GUIDE" -> repo.getName() + " — Onboarding Guide";
            case "API_REFERENCE" -> repo.getName() + " — API Reference";
            default -> repo.getName() + " — " + docType;
        };
    }

    /**
     * The workspace-scoped slug of an overview document.
     *
     * <p>The repository name is part of the slug, so two repositories
     * with the same name in one workspace do not collide.
     */
    private static String _slugFor(Repository repo, String docType) {
        String base = repo.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        String suffix = docType.toLowerCase(Locale.ROOT).replace('_', '-');
        return "repo-" + base + "-" + suffix;
    }

    private static AiDtos.RepositorySnapshot _toWireSnapshot(RepositorySnapshot snapshot) {
        List<AiDtos.RepositoryKeyFile> keyFiles = snapshot.keyFiles().stream()
                .map(kf -> new AiDtos.RepositoryKeyFile(kf.path(), kf.content(), kf.language()))
                .toList();
        return new AiDtos.RepositorySnapshot(
                snapshot.fullName(),
                snapshot.description(),
                snapshot.defaultBranch(),
                snapshot.tree(),
                snapshot.languages(),
                snapshot.directories(),
                snapshot.manifests(),
                snapshot.entryPoints(),
                keyFiles,
                snapshot.totalFiles(),
                snapshot.truncated());
    }

    /**
     * The GitHub credential to read the repository with.
     *
     * <p>The credential of the user who connected the repository is
     * preferred — that is the one whose scopes cover the repository.
     * The requesting user is the fallback.
     *
     * <p>Neither lookup goes through {@code CurrentUser}: the analysis
     * runs on a background thread, where there is no authenticated
     * request to read the current user from.
     */
    private String _resolveAccessToken(Repository repo, UUID requestedBy) {
        var connection = githubOAuthService.findForUser(repo.getConnectedBy());
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
    public Optional<RepositoryAnalysisJob> find(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    @Transactional(readOnly = true)
    public List<RepositoryAnalysisJob> recentForWorkspace(UUID workspaceId) {
        return jobRepository.findTop20ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * Render a job as the response body the frontend polls.
     *
     * <p>The stored {@code resultJson} carries the per-document
     * outcome; it is decoded here so the client does not have to parse
     * a nested string.
     */
    public Map<String, Object> toResponse(RepositoryAnalysisJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getId().toString());
        out.put("status", job.getStatus().name());
        out.put("repositoryId", job.getRepositoryId().toString());
        out.put("docTypes", _splitDocTypes(job.getDocTypes()));
        out.put("totalFiles", job.getTotalFiles());
        out.put("scannedFiles", job.getScannedFiles());
        out.put("processedDocuments", job.getProcessedDocuments());
        out.put("generated", job.getGeneratedDocuments());
        out.put("failed", job.getFailedDocuments());
        out.put("errorMessage", job.getErrorMessage());
        out.put("createdAt", job.getCreatedAt());
        out.put("startedAt", job.getStartedAt());
        out.put("finishedAt", job.getFinishedAt());

        if (job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                out.putAll(objectMapper.readValue(job.getResultJson(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Could not decode result of repository analysis job {}: {}",
                        job.getId(), e.getMessage());
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------

    /**
     * Write the running state in its own transaction.
     *
     * <p>The {@code REQUIRES_NEW} annotations this used to carry were a
     * no-op: Spring's transaction proxy is bypassed by a self-invocation
     * from {@link #run(UUID)}, so the propagation never took effect. A
     * {@link TransactionTemplate} is used instead because it does not
     * depend on the proxy being involved.
     */
    private void markRunning(UUID jobId) {
        tx.executeWithoutResult(status -> {
            RepositoryAnalysisJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setStatus(RepositoryAnalysisJobStatus.RUNNING);
            job.setStartedAt(OffsetDateTime.now());
            jobRepository.save(job);
        });
    }

    /**
     * Persist the running counters. Runs in its own transaction so the
     * progress survives a rollback of the enclosing work.
     */
    private void updateProgress(UUID jobId, int generated, int failed) {
        tx.executeWithoutResult(status -> {
            RepositoryAnalysisJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setGeneratedDocuments(generated);
            job.setFailedDocuments(failed);
            job.setProcessedDocuments(generated + failed);
            jobRepository.save(job);
        });
    }

    private void updateScanProgress(UUID jobId, int totalFiles, int scannedFiles) {
        tx.executeWithoutResult(status -> {
            RepositoryAnalysisJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setTotalFiles(totalFiles);
            job.setScannedFiles(scannedFiles);
            jobRepository.save(job);
        });
    }

    private void finish(UUID jobId, int generated,
                        List<String> errors, List<Map<String, Object>> documents) {
        tx.executeWithoutResult(status -> {
            RepositoryAnalysisJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;

            job.setGeneratedDocuments(generated);
            job.setFailedDocuments(errors.size());
            job.setProcessedDocuments(generated + errors.size());
            job.setFinishedAt(OffsetDateTime.now());

            // PARTIAL is distinct from SUCCEEDED: the user needs to know
            // that some document types failed, otherwise a run that wrote
            // three of four documents would look like a complete success.
            if (errors.isEmpty()) {
                job.setStatus(RepositoryAnalysisJobStatus.SUCCEEDED);
            } else if (generated > 0) {
                job.setStatus(RepositoryAnalysisJobStatus.PARTIAL);
            } else {
                job.setStatus(RepositoryAnalysisJobStatus.FAILED);
                job.setErrorMessage(errors.get(0));
            }

            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("generated", generated);
                payload.put("errors", errors);
                payload.put("documents", documents);
                job.setResultJson(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("Could not serialise repository analysis result for job {}: {}",
                        jobId, e.getMessage());
            }

            jobRepository.save(job);
            log.info("Repository analysis job {} finished: status={}, generated={}, failed={}",
                    jobId, job.getStatus(), generated, errors.size());
        });
    }

    private void markFailed(UUID jobId, String message) {
        tx.executeWithoutResult(status -> {
            RepositoryAnalysisJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setStatus(RepositoryAnalysisJobStatus.FAILED);
            job.setErrorMessage(message);
            job.setFinishedAt(OffsetDateTime.now());
            jobRepository.save(job);
        });
    }

    private static List<String> _normaliseDocTypes(List<String> requested) {
        List<String> effective = new ArrayList<>();
        if (requested != null) {
            for (String raw : requested) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String type = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
                if (type.startsWith("DEFAULT_")) {
                    type = type.substring("DEFAULT_".length());
                }
                if (!effective.contains(type)) {
                    effective.add(type);
                }
            }
        }
        if (effective.isEmpty()) {
            return DEFAULT_DOC_TYPES;
        }
        return effective;
    }

    private static List<String> _splitDocTypes(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String type = part.trim();
            if (!type.isEmpty()) {
                out.add(type);
            }
        }
        return out;
    }

    private static String _sourcesToJson(AiDtos.GenerateResponse resp) {
        if (resp == null || resp.sources() == null || resp.sources().isEmpty()) {
            return null;
        }
        try {
            return new ObjectMapper().writeValueAsString(resp.sources());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The outcome of generating one overview document.
     */
    private record DocumentOutcome(boolean success,
                                   UUID documentId,
                                   String title,
                                   String slug,
                                   Integer versionNumber,
                                   String errorMessage) {

        static DocumentOutcome success(UUID documentId, String title, String slug,
                                       Integer versionNumber) {
            return new DocumentOutcome(true, documentId, title, slug, versionNumber, null);
        }

        static DocumentOutcome failure(String errorMessage) {
            return new DocumentOutcome(false, null, null, null, null, errorMessage);
        }
    }
}
