package com.livingdocs.modules.ai.bulk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs a {@link BulkGenerationJob} on the background executor.
 *
 * <p>This lives in its own bean on purpose. Spring's {@code @Async}
 * support is implemented with a proxy, so calling an {@code @Async}
 * method from <em>within</em> the same bean bypasses the proxy and the
 * call runs synchronously on the calling thread. Splitting the worker
 * out means {@link BulkGenerationService} can hand the job over and
 * return to the HTTP request immediately.
 */
@Component
public class BulkGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(BulkGenerationWorker.class);

    private final BulkGenerationService service;

    public BulkGenerationWorker(BulkGenerationService service) {
        this.service = service;
    }

    /**
     * Background entry point for a bulk generation run.
     */
    @Async("indexingExecutor")
    public void run(UUID jobId) {
        log.info("Starting bulk generation job {}", jobId);
        service.runJob(jobId);
    }
}
