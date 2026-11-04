package com.livingdocs.modules.health.controller;

import com.livingdocs.common.AppConstants;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.health.dto.SystemHealthResponse;
import com.livingdocs.modules.health.service.SystemHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extended system health endpoint that checks database, AI service,
 * job queue, and JVM metrics.
 *
 * <p>This endpoint exposes <em>operational</em> information: database
 * latency, the AI service base URL, JVM heap usage, thread count and
 * the job-queue depth. That is a leak — a low-privilege user must not
 * be able to fingerprint the deployment. It is therefore gated behind
 * the {@code HEALTH_DASHBOARD_VIEW} permission rather than plain
 * authentication.
 *
 * <p>The plain liveness probe at {@code /api/v1/health}
 * ({@link HealthController}) stays open: it returns only a status
 * string and is used by the load balancer.
 */
@RestController
@RequestMapping(AppConstants.API_V1_PREFIX + "/system-health")
@RequirePermission({"HEALTH_DASHBOARD_VIEW"})
@Tag(name = "System Health", description = "Full system health check")
public class SystemHealthController {

    private final SystemHealthService systemHealthService;

    public SystemHealthController(SystemHealthService systemHealthService) {
        this.systemHealthService = systemHealthService;
    }

    @GetMapping
    @Operation(summary = "Get full system health snapshot")
    public ResponseEntity<SystemHealthResponse> getSystemHealth() {
        return ResponseEntity.ok(systemHealthService.checkAll());
    }
}
