package com.livingdocs.modules.drift.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.drift.dto.DriftAlertResponse;
import com.livingdocs.modules.drift.dto.IngestDriftAlertRequest;
import com.livingdocs.modules.drift.dto.ResolveDriftAlertRequest;
import com.livingdocs.modules.drift.model.DriftKind;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.service.DriftAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for documentation drift alerts.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Drift", description = "Documentation drift alerts and resolution")
public class DriftAlertController {

    private final DriftAlertService driftService;

    public DriftAlertController(DriftAlertService driftService) {
        this.driftService = driftService;
    }

    @PostMapping("/workspaces/{workspaceId}/drift-alerts")
    @Operation(summary = "Ingest a drift alert reported by the AI service")
    public ResponseEntity<DriftAlertResponse> ingest(@PathVariable UUID workspaceId,
                                                     @Valid @RequestBody IngestDriftAlertRequest req) {
        DriftAlertResponse out = driftService.ingest(CurrentUser.requireId(), workspaceId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/workspaces/{workspaceId}/drift-alerts")
    @Operation(summary = "List drift alerts in a workspace with optional filters")
    public List<DriftAlertResponse> list(@PathVariable UUID workspaceId,
                                         @RequestParam(required = false) DriftResolution status,
                                         @RequestParam(required = false) DriftKind kind,
                                         @RequestParam(required = false) DriftSeverity severity) {
        return driftService.listForWorkspace(CurrentUser.requireId(), workspaceId, status, kind, severity);
    }

    @GetMapping("/workspaces/{workspaceId}/drift-alerts/{alertId}")
    @Operation(summary = "Fetch a single drift alert")
    public DriftAlertResponse get(@PathVariable UUID workspaceId,
                                  @PathVariable UUID alertId) {
        return driftService.get(CurrentUser.requireId(), alertId);
    }

    @PostMapping("/workspaces/{workspaceId}/drift-alerts/{alertId}/resolve")
    @Operation(summary = "Resolve (accept / dismiss / mark fixed) a drift alert")
    public DriftAlertResponse resolve(@PathVariable UUID workspaceId,
                                      @PathVariable UUID alertId,
                                      @Valid @RequestBody ResolveDriftAlertRequest req) {
        return driftService.resolve(CurrentUser.requireId(), alertId, req);
    }
}