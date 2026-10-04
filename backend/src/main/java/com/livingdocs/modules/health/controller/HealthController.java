package com.livingdocs.modules.health.controller;

import com.livingdocs.common.AppConstants;
import com.livingdocs.modules.health.dto.HealthResponse;
import com.livingdocs.modules.health.service.HealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health endpoint.
 *
 * <p>Used to verify the modular architecture wiring (controller → service → dto)
 * and to provide a simple liveness probe. No business logic is performed here.
 */
@RestController
@RequestMapping(AppConstants.API_V1_PREFIX + "/health")
@Tag(name = "Health", description = "Service liveness probe")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    @Operation(summary = "Get service health",
            description = "Returns 200 OK when the backend is running.")
    @ApiResponse(responseCode = "200", description = "Service is up")
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(healthService.check());
    }
}