package com.livingdocs.modules.health.service;

import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.ai.config.AiServiceProperties;
import com.livingdocs.modules.ai.indexing.IndexJob;
import com.livingdocs.modules.ai.indexing.IndexJobRepository;
import com.livingdocs.modules.ai.indexing.IndexJobStatus;
import com.livingdocs.modules.health.dto.SystemHealthResponse;
import com.livingdocs.modules.health.dto.SystemHealthResponse.AiServiceHealth;
import com.livingdocs.modules.health.dto.SystemHealthResponse.DatabaseHealth;
import com.livingdocs.modules.health.dto.SystemHealthResponse.JobQueueStats;
import com.livingdocs.modules.health.dto.SystemHealthResponse.SystemMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Service that aggregates system health across multiple components:
 * database, AI service, job queue, and JVM metrics.
 */
@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);

    private final DataSource dataSource;
    private final AiServiceProperties aiProperties;
    private final IndexJobRepository indexJobRepository;
    private final RestClient restClient;

    public SystemHealthService(
            DataSource dataSource,
            AiServiceProperties aiProperties,
            IndexJobRepository indexJobRepository) {
        this.dataSource = dataSource;
        this.aiProperties = aiProperties;
        this.indexJobRepository = indexJobRepository;
        this.restClient = RestClient.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .build();
    }

    public SystemHealthResponse checkAll() {
        DatabaseHealth db = checkDatabase();
        AiServiceHealth ai = checkAiService();
        JobQueueStats jobs = getJobQueueStats();
        SystemMetrics metrics = getSystemMetrics();

        String overall = computeOverallStatus(db, ai);

        return new SystemHealthResponse(
                overall,
                OffsetDateTime.now(),
                db,
                ai,
                jobs,
                metrics
        );
    }

    private String computeOverallStatus(DatabaseHealth db, AiServiceHealth ai) {
        if ("DOWN".equals(db.status())) return "DOWN";
        if ("DOWN".equals(ai.status())) return "DEGRADED";
        return "UP";
    }

    private DatabaseHealth checkDatabase() {
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(5);
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            if (valid) {
                return DatabaseHealth.up(latencyMs);
            }
            return DatabaseHealth.down(latencyMs, "Connection validation failed");
        } catch (Exception e) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.warn("Database health check failed", e);
            return DatabaseHealth.down(latencyMs, e.getMessage());
        }
    }

    private AiServiceHealth checkAiService() {
        if (!aiProperties.isEnabled()) {
            return AiServiceHealth.disabled(aiProperties.getBaseUrl());
        }
        long start = System.nanoTime();
        try {
            // Try the AI service health endpoint with a short timeout
            String url = aiProperties.getBaseUrl() + aiProperties.getApiPrefix() + "/health";
            restClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity();
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return AiServiceHealth.up(latencyMs, aiProperties.getBaseUrl());
        } catch (Exception e) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.warn("AI service health check failed: {}", e.getMessage());
            return AiServiceHealth.down(latencyMs, aiProperties.getBaseUrl(), e.getMessage());
        }
    }

    private JobQueueStats getJobQueueStats() {
        try {
            Map<IndexJobStatus, Integer> counts = new EnumMap<>(IndexJobStatus.class);
            for (IndexJobStatus status : IndexJobStatus.values()) {
                counts.put(status, 0);
            }
            // Iterate all recent jobs to count by status
            indexJobRepository.findAll().forEach(job -> {
                counts.merge(job.getStatus(), 1, Integer::sum);
            });
            return new JobQueueStats(
                    counts.get(IndexJobStatus.PENDING),
                    counts.get(IndexJobStatus.RUNNING),
                    counts.get(IndexJobStatus.COMPLETED),
                    counts.get(IndexJobStatus.FAILED),
                    counts.get(IndexJobStatus.CANCELLED)
            );
        } catch (Exception e) {
            log.warn("Failed to fetch job queue stats: {}", e.getMessage());
            return JobQueueStats.empty();
        }
    }

    private SystemMetrics getSystemMetrics() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        return new SystemMetrics(
                usedMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                threads,
                uptime
        );
    }
}
