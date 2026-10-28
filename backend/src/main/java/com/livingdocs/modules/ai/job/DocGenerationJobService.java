package com.livingdocs.modules.ai.job;

import com.livingdocs.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and resolves {@link DocGenerationJob} rows.
 *
 * <p>Every job carries a {@code correlationId} that is threaded through
 * the whole event chain (request → Kafka → AI worker → persistence
 * worker). That id is the idempotency key: if the persistence worker
 * sees a job it already completed, it skips the write instead of
 * creating a duplicate document version.
 */
@Service
public class DocGenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(DocGenerationJobService.class);

    private final DocGenerationJobRepository repository;

    public DocGenerationJobService(DocGenerationJobRepository repository) {
        this.repository = repository;
    }

    /**
     * Open a new job. Returns the job id, which doubles as the
     * correlation id for the event chain.
     */
    @Transactional
    public UUID open(UUID workspaceId,
                     UUID documentId,
                     UUID requestedBy,
                     String template,
                     UUID correlationId) {
        UUID id = correlationId != null ? correlationId : UUID.randomUUID();
        // Re-use an existing job when the correlation id is already known
        // (Kafka redelivery) so we never create two rows for one run.
        DocGenerationJob existing = repository.findByCorrelationId(id).orElse(null);
        if (existing != null) {
            log.debug("Generation job {} already exists, re-using it", id);
            return id;
        }
        repository.save(new DocGenerationJob(id, workspaceId, documentId, id, requestedBy, template));
        return id;
    }

    @Transactional(readOnly = true)
    public Optional<DocGenerationJob> findByCorrelationId(UUID correlationId) {
        if (correlationId == null) return Optional.empty();
        return repository.findByCorrelationId(correlationId);
    }

    @Transactional(readOnly = true)
    public Optional<DocGenerationJob> latestForDocument(UUID documentId) {
        return repository.findFirstByDocumentIdOrderByCreatedAtDesc(documentId);
    }

    @Transactional(readOnly = true)
    public List<DocGenerationJob> listForDocument(UUID documentId) {
        return repository.findAllByDocumentIdOrderByCreatedAtDesc(documentId);
    }

    @Transactional(readOnly = true)
    public DocGenerationJob require(UUID correlationId) {
        return repository.findByCorrelationId(correlationId)
                .orElseThrow(() -> new NotFoundException(
                        "Generation job not found: " + correlationId));
    }

    /**
     * Mark a job as successful and link it to the version it produced.
     *
     * <p>Runs in its own transaction so the job update survives even if
     * the surrounding listener transaction is rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID correlationId, UUID versionId, int promptTokens, int completionTokens) {
        DocGenerationJob job = repository.findByCorrelationId(correlationId).orElse(null);
        if (job == null) {
            log.warn("No generation job for correlation {} — cannot mark succeeded", correlationId);
            return;
        }
        if (job.getStatus() != DocGenerationJob.Status.RUNNING) {
            log.info("Generation job {} already {} — skipping completion",
                    correlationId, job.getStatus());
            return;
        }
        job.succeed(versionId, promptTokens, completionTokens);
        repository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID correlationId, String errorMessage) {
        DocGenerationJob job = repository.findByCorrelationId(correlationId).orElse(null);
        if (job == null) {
            log.warn("No generation job for correlation {} — cannot mark failed", correlationId);
            return;
        }
        if (job.getStatus() != DocGenerationJob.Status.RUNNING) {
            return;
        }
        job.fail(errorMessage);
        repository.save(job);
    }
}