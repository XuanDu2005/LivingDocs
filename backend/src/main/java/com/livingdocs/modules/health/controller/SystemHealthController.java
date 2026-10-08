package com.livingdocs.modules.health.controller;

import com.livingdocs.common.AppConstants;
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
 */
@RestController
@RequestMapping(AppConstants.API_V1_PREFIX + "/system-health")
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
