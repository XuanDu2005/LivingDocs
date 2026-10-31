package com.livingdocs.modules.ai.bulk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the bulk "generate documentation for every undocumented entity"
 * workflow in the background.
 *
 * <p>Generating one document per entity means one LLM call per entity. A
 * repository with a dozen undocumented entities therefore needs well over
 * a minute — far too long for a synchronous HTTP request, which is why
 * the controller enqueues a job and answers 202 instead.
 *
 * <p>The job runs on the {@code indexingExecutor} pool. Progress is
 * written back to the {@link BulkGenerationJob} row as each entity is
 * processed, so the UI can show a live counter and — crucially — see
 * exactly which entities failed instead of waiting for a single opaque
 * response.
 */
@Service
public class BulkGenerationService {

    private static final Logger log = LoggerFactory.getLogger(BulkGenerationService.class);

    private final BulkGenerationJobRepository jobRepository;
    private final CodeEntityRepository codeEntityRepository;
    private final RepositoryRepository repositoryRepository;
    private final AiDocumentationService aiDocumentationService;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    /**
     * The worker depends on this service, so the back-reference is
     * injected lazily. Without {@code @Lazy} the two beans form a
     * circular dependency, which Spring refuses to resolve.
     */
    private final BulkGenerationWorker worker;

    public BulkGenerationService(BulkGenerationJobRepository jobRepository,
                                CodeEntityRepository codeEntityRepository,
                                RepositoryRepository repositoryRepository,
                                AiDocumentationService aiDocumentationService,
                                WorkspaceService workspaceService,
                                ObjectMapper objectMapper,
                                @Lazy BulkGenerationWorker worker) {
        this.jobRepository = jobRepository;
        this.codeEntityRepository = codeEntityRepository;
        this.repositoryRepository = repositoryRepository;
        this.aiDocumentationService = aiDocumentationService;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
        this.worker = worker;
    }

    /**
     * Enqueue a bulk generation run.
     *
     * <p>Returns the job that tracks the run. When a run for the same
     * repository is already in flight, that job is returned instead of
     * starting a second one — two concurrent bulk runs would race on the
     * same documents and burn the LLM budget twice.
     */
    @Transactional
    public BulkGenerationJob enqueue(UUID actorId, UUID workspaceId,
                                     UUID repositoryId, String template) {
        workspaceService.requireMember(actorId, workspaceId);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Repository not found in this workspace");
        }

        List<BulkGenerationJob> active = jobRepository.findActiveForRepository(repositoryId);
        if (!active.isEmpty()) {
            log.info("Bulk generation already running for repository {} — reusing job {}",
                    repositoryId, active.get(0).getId());
            return active.get(0);
        }

        List<CodeEntity> entities =
                codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId);

        BulkGenerationJob job = new BulkGenerationJob();
        job.setWorkspaceId(workspaceId);
        job.setRepositoryId(repositoryId);
        job.setTemplate(template);
        job.setRequestedBy(actorId);
        job.setTotalEntities(entities.size());
        job.setStatus(BulkGenerationJobStatus.PENDING);
        job.setCreatedAt(OffsetDateTime.now());
        BulkGenerationJob saved = jobRepository.save(job);

        // Hand the job over to the background worker *after* the
        // transaction commits, so the row is visible to the worker when
        // it starts. Handing it over inside the transaction would let the
        // worker pick the job up before it was committed.
        startAfterCommit(saved.getId());

        return saved;
    }

    /**
     * Schedule the worker once the current transaction has committed.
     *
     * <p>When there is no active transaction the worker starts
     * immediately.
     */
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

    /**
     * Process every undocumented entity of the repository.
     *
     * <p>Called by {@link BulkGenerationWorker} on the background
     * executor.
     */
    public void runJob(UUID jobId) {
        try {
            run(jobId);
        } catch (Exception ex) {
            log.error("Bulk generation job {} crashed: {}", jobId, ex.getMessage(), ex);
            try {
                markFailed(jobId, ex.getMessage());
            } catch (Exception ignore) {
                // Last-resort failure marker; never throw further.
            }
        }
    }

    /**
     * Process every undocumented entity of the repository.
     */
    @Transactional
    public void run(UUID jobId) {
        BulkGenerationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() != BulkGenerationJobStatus.PENDING) return;

        job.setStatus(BulkGenerationJobStatus.RUNNING);
        job.setStartedAt(OffsetDateTime.now());
        jobRepository.save(job);

        Repository repo = repositoryRepository.findById(job.getRepositoryId()).orElse(null);
        if (repo == null) {
            markFailed(jobId, "Repository " + job.getRepositoryId() + " no longer exists");
            return;
        }

        List<CodeEntity> entities =
                codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(job.getRepositoryId());

        List<Map<String, Object>> documents = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int generated = 0;
        int skipped = 0;

        for (CodeEntity entity : entities) {
            // An entity that already carries a docstring is documented
            // already — there is nothing for the AI to add.
            if (entity.getDocstring() != null && !entity.getDocstring().isBlank()) {
                skipped++;
                updateProgress(jobId, skipped, generated, errors.size());
                continue;
            }

            try {
                AiDocumentationService.EntityGenerationOutcome outcome =
                        aiDocumentationService.generateDocumentForEntity(
                                job.getRequestedBy(), job.getWorkspaceId(), repo, entity,
                                job.getTemplate());

                if (outcome.success()) {
                    generated++;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("entity", outcome.qualifiedName());
                    row.put("documentId", outcome.documentId().toString());
                    row.put("title", outcome.title());
                    row.put("versionNumber", outcome.versionNumber());
                    documents.add(row);
                } else {
                    errors.add(outcome.errorMessage());
                }
            } catch (Exception e) {
                log.error("Failed to generate doc for entity {}: {}",
                        entity.getQualifiedName(), e.getMessage());
                errors.add("Failed for " + entity.getQualifiedName() + ": " + e.getMessage());
            }

            updateProgress(jobId, skipped, generated, errors.size());
        }

        finish(jobId, generated, skipped, errors, documents);
    }

    @Transactional(readOnly = true)
    public Optional<BulkGenerationJob> find(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    @Transactional(readOnly = true)
    public List<BulkGenerationJob> recentForWorkspace(UUID workspaceId) {
        return jobRepository.findTop20ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * Render a job as the response body the frontend polls.
     *
     * <p>The stored {@code resultJson} carries the per-entity outcome;
     * it is decoded here so the client does not have to parse a nested
     * string.
     */
    public Map<String, Object> toResponse(BulkGenerationJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getId().toString());
        out.put("status", job.getStatus().name());
        out.put("repositoryId", job.getRepositoryId().toString());
        out.put("template", job.getTemplate());
        out.put("totalEntities", job.getTotalEntities());
        out.put("processedEntities", job.getProcessedEntities());
        out.put("generated", job.getGeneratedDocuments());
        out.put("skipped", job.getSkippedEntities());
        out.put("failed", job.getFailedEntities());
        out.put("errorMessage", job.getErrorMessage());
        out.put("createdAt", job.getCreatedAt());
        out.put("startedAt", job.getStartedAt());
        out.put("finishedAt", job.getFinishedAt());

        if (job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                out.putAll(objectMapper.readValue(job.getResultJson(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Could not decode result of bulk generation job {}: {}", job.getId(), e.getMessage());
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------

    /**
     * Persist the running counters. Runs in its own transaction so the
     * progress survives a rollback of the enclosing work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID jobId, int skipped, int generated, int failed) {
        BulkGenerationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setSkippedEntities(skipped);
        job.setGeneratedDocuments(generated);
        job.setFailedEntities(failed);
        job.setProcessedEntities(skipped + generated + failed);
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID jobId, int generated, int skipped,
                       List<String> errors, List<Map<String, Object>> documents) {
        BulkGenerationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        job.setGeneratedDocuments(generated);
        job.setSkippedEntities(skipped);
        job.setFailedEntities(errors.size());
        job.setProcessedEntities(skipped + generated + errors.size());
        job.setFinishedAt(OffsetDateTime.now());

        // PARTIAL is distinct from SUCCEEDED: the user needs to know that
        // some entities failed, otherwise a run that wrote 3 of 13
        // documents would look like a complete success.
        if (errors.isEmpty()) {
            job.setStatus(BulkGenerationJobStatus.SUCCEEDED);
        } else if (generated > 0) {
            job.setStatus(BulkGenerationJobStatus.PARTIAL);
        } else {
            job.setStatus(BulkGenerationJobStatus.FAILED);
            job.setErrorMessage(errors.get(0));
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("generated", generated);
            payload.put("skipped", skipped);
            payload.put("errors", errors);
            payload.put("documents", documents);
            job.setResultJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Could not serialise bulk generation result for job {}: {}", jobId, e.getMessage());
        }

        jobRepository.save(job);
        log.info("Bulk generation job {} finished: status={}, generated={}, skipped={}, failed={}",
                jobId, job.getStatus(), generated, skipped, errors.size());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String message) {
        BulkGenerationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus(BulkGenerationJobStatus.FAILED);
        job.setErrorMessage(message);
        job.setFinishedAt(OffsetDateTime.now());
        jobRepository.save(job);
    }
}
