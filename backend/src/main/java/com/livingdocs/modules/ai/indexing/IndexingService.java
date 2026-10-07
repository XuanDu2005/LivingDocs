package com.livingdocs.modules.ai.indexing;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Indexing job orchestration.
 *
 * <p>This service is the entry point for two operations:
 * <ol>
 *   <li>Enqueue a single-document index.</li>
 *   <li>Enqueue a full-workspace reindex.</li>
 * </ol>
 *
 * <p>Both create an {@link IndexJob} row synchronously (so the caller
 * gets back a job id they can poll) and then dispatch the actual work
 * to a background executor via {@link Async}. The executor runs in a
 * separate transaction so they don't hold up the HTTP response.
 *
 * <p>The service is also responsible for the lifecycle transitions:
 * PENDING -> RUNNING, incrementing counters as each document is
 * indexed, and writing the final COMPLETED / FAILED / CANCELLED row.
 * Callers can poll the status via {@code IndexingController}.
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final IndexJobRepository jobRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionService versionService;
    private final AiServiceClient aiClient;
    private final WorkspaceService workspaceService;

    public IndexingService(IndexJobRepository jobRepository,
                           DocumentRepository documentRepository,
                           DocumentVersionService versionService,
                           AiServiceClient aiClient,
                           WorkspaceService workspaceService) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.versionService = versionService;
        this.aiClient = aiClient;
        this.workspaceService = workspaceService;
    }

    /**
     * Enqueue a single-document indexing job. Returns the persisted
     * job so the caller can immediately poll for status.
     *
     * <p>Throws {@link IllegalStateException} if a job is already
     * running for the same (workspace, document).
     */
    @Transactional
    public IndexJob enqueueIndex(UUID workspaceId, UUID documentId, UUID actorId) {
        workspaceService.requireMember(actorId, workspaceId);
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        if (!doc.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Document does not belong to workspace");
        }
        if (jobRepository.existsActiveForDocument(workspaceId, documentId)) {
            throw new IllegalStateException("An indexing job is already running for this document");
        }

        IndexJob job = new IndexJob();
        job.setWorkspaceId(workspaceId);
        job.setDocumentId(documentId);
        job.setKind(IndexJobKind.INDEX);
        job.setStatus(IndexJobStatus.PENDING);
        job.setTotalTargets(1);
        job.setCreatedBy(actorId);
        OffsetDateTime now = OffsetDateTime.now();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        IndexJob saved = jobRepository.save(job);

        // Hand off to the background executor. The worker is in a
        // different transaction so it can run for arbitrarily long.
        runIndexJobAsync(saved.getId());
        return saved;
    }

    /**
     * Enqueue a full-workspace reindex. Returns the persisted job.
     */
    @Transactional
    public IndexJob enqueueReindexAll(UUID workspaceId, UUID actorId) {
        workspaceService.requireMember(actorId, workspaceId);
        if (jobRepository.existsActiveReindexAll(workspaceId)) {
            throw new IllegalStateException("A reindex job is already running for this workspace");
        }

        // Pre-count so the UI can show a progress bar before the worker
        // even picks the job up.
        long total = documentRepository.countByWorkspaceId(workspaceId);

        IndexJob job = new IndexJob();
        job.setWorkspaceId(workspaceId);
        job.setDocumentId(null);
        job.setKind(IndexJobKind.REINDEX_ALL);
        job.setStatus(IndexJobStatus.PENDING);
        job.setTotalTargets((int) total);
        job.setCreatedBy(actorId);
        OffsetDateTime now = OffsetDateTime.now();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        IndexJob saved = jobRepository.save(job);

        runIndexJobAsync(saved.getId());
        return saved;
    }

    /**
     * Mark a job as cancelled. No-op if it has already finished.
     */
    @Transactional
    public IndexJob cancel(UUID jobId, UUID actorId) {
        IndexJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Indexing job not found"));
        workspaceService.requireMember(actorId, job.getWorkspaceId());
        if (job.getStatus() == IndexJobStatus.COMPLETED
                || job.getStatus() == IndexJobStatus.FAILED
                || job.getStatus() == IndexJobStatus.CANCELLED) {
            return job;
        }
        job.setStatus(IndexJobStatus.CANCELLED);
        job.setFinishedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        return jobRepository.save(job);
    }

    /**
     * Asynchronous worker entry point. Runs on the
     * {@code indexingExecutor} pool. Each job runs in its own
     * transaction so partial progress survives a failure.
     */
    @Async("indexingExecutor")
    public void runIndexJobAsync(UUID jobId) {
        try {
            runIndexJob(jobId);
        } catch (Exception ex) {
            log.error("Indexing job {} crashed: {}", jobId, ex.getMessage(), ex);
            try {
                markFailed(jobId, ex.getMessage());
            } catch (Exception ignore) {
                // Last-resort failure marker; don't throw further.
            }
        }
    }

    /**
     * Run a job from PENDING -> RUNNING -> COMPLETED/FAILED. Each
     * document's index call is wrapped in its own transaction so a
     * single failure doesn't poison the rest of the batch.
     */
    @Transactional
    public void runIndexJob(UUID jobId) {
        IndexJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() != IndexJobStatus.PENDING) return;

        job.setStatus(IndexJobStatus.RUNNING);
        job.setStartedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.save(job);

        try {
            if (job.getKind() == IndexJobKind.INDEX) {
                processTargetForIndexDocument(job);
            } else {
                processReindexAll(job);
            }
            // Re-read for the final state: processTarget may have set
            // the status to FAILED already, or it may have been
            // cancelled mid-flight by a user.
            IndexJob current = jobRepository.findById(jobId).orElse(job);
            if (current.getStatus() == IndexJobStatus.RUNNING) {
                current.setStatus(IndexJobStatus.COMPLETED);
            }
            current.setFinishedAt(OffsetDateTime.now());
            current.setUpdatedAt(OffsetDateTime.now());
            jobRepository.save(current);
        } catch (Exception ex) {
            job.setStatus(IndexJobStatus.FAILED);
            job.setErrorMessage(ex.getMessage());
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            jobRepository.save(job);
            throw ex;
        }
    }

    /**
     * Index exactly the document the job was created for.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTargetForIndexDocument(IndexJob job) {
        UUID documentId = job.getDocumentId();
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            job.setFailedTargets(job.getFailedTargets() + 1);
            job.setErrorMessage("Document " + documentId + " disappeared");
            return;
        }
        indexOneDocument(job, document);
    }

    /**
     * Iterate every document in the workspace and index them in turn.
     */
    private void processReindexAll(IndexJob job) {
        List<Document> docs = documentRepository
                .findAllByWorkspaceIdOrderByUpdatedAtDesc(job.getWorkspaceId());
        if (docs.isEmpty()) {
            job.setTotalTargets(0);
            return;
        }
        // totalTargets was pre-computed but the repository can lie if
        // documents have been created since; sync to the actual list.
        job.setTotalTargets(docs.size());
        for (Document doc : docs) {
            if (job.getStatus() == IndexJobStatus.CANCELLED) return;
            indexOneDocument(job, doc);
        }
    }

    /**
     * Issue the AI service request and bump counters. Failures don't
     * throw — they are recorded as a failed target so the rest of the
     * batch keeps going.
     */
    private void indexOneDocument(IndexJob job, Document doc) {
        try {
            String body = versionService.findLatestBody(doc.getId()).orElse("");
            AiDtos.IndexResponse resp = aiClient.indexDocument(
                    doc.getId().toString(),
                    doc.getTitle(),
                    body,
                    doc.getDocType(),
                    job.getWorkspaceId().toString(),
                    Map.of(
                            "slug", doc.getSlug(),
                            "owner", doc.getOwnerId().toString(),
                            "jobId", job.getId().toString()));
            int chunks = resp == null ? 0 : resp.chunksIndexed();
            job.setChunksIndexed(job.getChunksIndexed() + chunks);
            job.setProcessedTargets(job.getProcessedTargets() + 1);
        } catch (Exception ex) {
            job.setFailedTargets(job.getFailedTargets() + 1);
            job.setErrorMessage(ex.getMessage());
            log.warn("Indexing job {}: document {} failed: {}",
                    job.getId(), doc.getId(), ex.getMessage());
        }
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.save(job);
    }

    @Transactional
    public void markFailed(UUID jobId, String message) {
        IndexJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus(IndexJobStatus.FAILED);
        job.setErrorMessage(message);
        job.setFinishedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.save(job);
    }

    /**
     * Paginated job listing for the dashboard. Membership check is
     * applied at the controller layer to keep this method free of
     * authorization concerns.
     */
    public org.springframework.data.domain.Page<IndexJob> listForWorkspace(
            UUID workspaceId, org.springframework.data.domain.Pageable pageable) {
        return jobRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, pageable);
    }

    /**
     * Lightweight "last 50" listing for the workspace sidebar/dashboard.
     */
    public java.util.List<IndexJob> recentForWorkspace(UUID workspaceId) {
        return jobRepository.findTop50ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * Fetch a single job, scoped to a workspace so a user can't peek at
     * jobs in workspaces they don't belong to.
     */
    public java.util.Optional<IndexJob> find(UUID workspaceId, UUID jobId) {
        return jobRepository.findById(jobId)
                .filter(j -> j.getWorkspaceId().equals(workspaceId));
    }
}