package com.livingdocs.modules.ai.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs a {@link RepositoryAnalysisJob} on the background executor.
 *
 * <p>This lives in its own bean on purpose. Spring's {@code @Async}
 * support is implemented with a proxy, so calling an {@code @Async}
 * method from <em>within</em> the same bean bypasses the proxy and the
 * call runs synchronously on the calling thread. Splitting the worker
 * out means {@link RepositoryAnalysisService} can hand the job over and
 * return to the HTTP request immediately.
 */
@Component
public class RepositoryAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(RepositoryAnalysisWorker.class);

    private final RepositoryAnalysisService service;

    public RepositoryAnalysisWorker(RepositoryAnalysisService service) {
        this.service = service;
    }

    /**
     * Background entry point for a repository analysis run.
     */
    @Async("indexingExecutor")
    public void run(UUID jobId) {
        log.info("Starting repository analysis job {}", jobId);
        service.runJob(jobId);
    }
}
