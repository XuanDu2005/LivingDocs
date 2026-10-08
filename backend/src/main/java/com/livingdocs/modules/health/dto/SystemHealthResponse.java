package com.livingdocs.modules.health.dto;

import java.time.OffsetDateTime;

/**
 * Full system health snapshot used by the health dashboard.
 */
public record SystemHealthResponse(
    String overall,
    OffsetDateTime checkedAt,
    DatabaseHealth database,
    AiServiceHealth aiService,
    JobQueueStats jobQueue,
    SystemMetrics systemMetrics
) {
    public record DatabaseHealth(String status, long latencyMs, String error) {
        public static DatabaseHealth up(long latency) {
            return new DatabaseHealth("UP", latency, null);
        }
        public static DatabaseHealth down(long latency, String error) {
            return new DatabaseHealth("DOWN", latency, error);
        }
    }

    public record AiServiceHealth(String status, long latencyMs, String endpoint, String error) {
        public static AiServiceHealth up(long latency, String endpoint) {
            return new AiServiceHealth("UP", latency, endpoint, null);
        }
        public static AiServiceHealth down(long latency, String endpoint, String error) {
            return new AiServiceHealth("DOWN", latency, endpoint, error);
        }
        public static AiServiceHealth disabled(String endpoint) {
            return new AiServiceHealth("DISABLED", 0, endpoint, null);
        }
    }

    public record JobQueueStats(int pending, int running, int completed, int failed, int cancelled) {
        public static JobQueueStats empty() {
            return new JobQueueStats(0, 0, 0, 0, 0);
        }
    }

    public record SystemMetrics(long usedMemoryMb, long maxMemoryMb, int threadCount, long uptimeSeconds) {
        public double memoryUsagePercent() {
            if (maxMemoryMb == 0) return 0;
            return (double) usedMemoryMb / maxMemoryMb * 100;
        }
    }
}
