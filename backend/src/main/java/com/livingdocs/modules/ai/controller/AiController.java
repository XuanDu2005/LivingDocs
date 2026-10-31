package com.livingdocs.modules.ai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.bulk.BulkGenerationJob;
import com.livingdocs.modules.ai.bulk.BulkGenerationService;
import com.livingdocs.modules.ai.dto.PreviewGenerateResponse;
import com.livingdocs.modules.ai.job.DocGenerationJob;
import com.livingdocs.modules.ai.job.DocGenerationJobResponse;
import com.livingdocs.modules.ai.job.DocGenerationJobService;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.drift.dto.DriftAlertResponse;
import com.livingdocs.modules.drift.service.DriftAlertService;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints that drive the AI-assisted workflows.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI", description = "AI-assisted documentation flows")
public class AiController {

    private final AiDocumentationService aiService;
    private final DocumentVersionService versionService;
    private final DriftAlertService driftService;
    private final DocumentVersionRepository versionRepository;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final DocGenerationJobService jobService;
    private final BulkGenerationService bulkGenerationService;

    public AiController(AiDocumentationService aiService,
                        DocumentVersionService versionService,
                        DriftAlertService driftService,
                        DocumentVersionRepository versionRepository,
                        WorkspaceService workspaceService,
                        ObjectMapper objectMapper,
                        DocGenerationJobService jobService,
                        BulkGenerationService bulkGenerationService) {
        this.aiService = aiService;
        this.versionService = versionService;
        this.driftService = driftService;
        this.versionRepository = versionRepository;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
        this.jobService = jobService;
        this.bulkGenerationService = bulkGenerationService;
    }

    /**
     * Queue an AI generation run.
     *
     * <p>Returns <b>202 Accepted</b> because the LLM call is
     * asynchronous: the request is queued on Kafka and processed by
     * the generation worker. The response body carries the job id
     * which can be polled via {@code /generation-status}.
     */
    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/generate")
    @Operation(summary = "Queue an AI documentation generation run (returns 202)")
    public ResponseEntity<Map<String, Object>> generate(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @RequestParam(required = false, defaultValue = "MODULE_GUIDE") String template,
            @RequestParam(required = false) String focus,
            @RequestBody List<AiDtos.SourceFile> files) {
        UUID correlationId = aiService.generateForDocument(
                CurrentUser.requireId(), documentId, files, template, null, focus, null, null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "ACCEPTED",
                "jobId", correlationId.toString(),
                "documentId", documentId.toString(),
                "message", "AI generation queued. Poll the generation-status endpoint for the result."));
    }

    /**
     * Poll the state of the most recent generation run for a document.
     *
     * <p>Returns {@code status: RUNNING} while the worker is busy,
     * {@code SUCCEEDED} with a {@code documentVersionId} when the
     * version has been written, or {@code FAILED} with the error.
     */
    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/generation-status")
    @Operation(summary = "Poll the status of the latest AI generation run for a document")
    public ResponseEntity<Map<String, Object>> generationStatus(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        DocGenerationJob job = jobService.latestForDocument(documentId).orElse(null);
        if (job == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "NONE",
                    "documentId", documentId.toString(),
                    "message", "No generation run has been queued for this document yet."));
        }
        return ResponseEntity.ok(DocGenerationJobResponse.from(job).toMap());
    }

    @PostMapping("/workspaces/{workspaceId}/drift-alerts/detect")
    @Operation(summary = "Run drift detection for a document against a code change")
    public Map<String, Object> detectDrift(@PathVariable UUID workspaceId,
                                           @RequestParam UUID repositoryId,
                                           @RequestParam(required = false) UUID pullRequestId,
                                           @RequestParam UUID documentId,
                                           @RequestBody DriftDetectionBody body) {
        int count = aiService.detectDriftForPullRequest(
                CurrentUser.requireId(), workspaceId, repositoryId,
                pullRequestId, documentId,
                body.filesBefore() == null ? List.of() : body.filesBefore(),
                body.filesAfter() == null ? List.of() : body.filesAfter());
        List<DriftAlertResponse> alerts = driftService.listForDocument(
                CurrentUser.requireId(), documentId);
        return Map.of("ingested", count, "alerts", alerts);
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/regenerate")
    @Operation(summary = "Manually trigger AI to regenerate documentation")
    public ResponseEntity<Map<String, Object>> regenerate(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId) {
        UUID correlationId = aiService.regenerateOnDemand(
                CurrentUser.requireId(), workspaceId, documentId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "ACCEPTED",
                "jobId", correlationId.toString(),
                "documentId", documentId.toString(),
                "message", "Regeneration queued."));
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/preview-generate")
    @Operation(summary = "Preview the AI-generated documentation without persisting a version")
    public PreviewGenerateResponse previewGenerate(@PathVariable UUID workspaceId,
                                                    @PathVariable UUID documentId,
                                                    @RequestParam(required = false, defaultValue = "MODULE_GUIDE") String template,
                                                    @RequestBody List<AiDtos.SourceFile> files) {
        var resp = aiService.previewForDocument(CurrentUser.requireId(), documentId, files, template);
        return PreviewGenerateResponse.of(resp, template);
    }

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/grounding")
    @Operation(summary = "Fetch the grounding evidence for a specific AI-generated version")
    public Map<String, Object> grounding(@PathVariable UUID workspaceId,
                                          @PathVariable UUID documentId,
                                          @PathVariable UUID versionId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        DocumentVersion v = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!v.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version does not belong to this document");
        }
        String json = v.getGroundingJson();
        if (json == null || json.isBlank()) {
            return Map.of("sources", List.of());
        }
        try {
            List<AiDtos.GroundingSource> sources = objectMapper.readValue(
                    json, new TypeReference<List<AiDtos.GroundingSource>>() {});
            return Map.of("sources", sources);
        } catch (Exception e) {
            return Map.of("sources", List.of(), "raw", json);
        }
    }

    /** Convenience wrapper for the request body. */
    public record DriftDetectionBody(
            List<AiDtos.SourceFile> filesBefore,
            List<AiDtos.SourceFile> filesAfter) {}

    /**
     * Generate documentation for all coverage-gap entities in a repository.
     *
     * <p>This is a bulk operation: one LLM call per undocumented entity,
     * which takes far longer than a normal request may block. The call
     * therefore enqueues a job and answers <b>202 Accepted</b>; the
     * client polls {@code /bulk-generation-jobs/{jobId}} for progress.
     */
    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/generate-all")
    @Operation(summary = "Queue a bulk documentation generation run for a repository (returns 202)")
    public ResponseEntity<Map<String, Object>> generateAllForRepository(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(required = false, defaultValue = "MODULE_GUIDE") String template) {
        BulkGenerationJob job = bulkGenerationService.enqueue(
                CurrentUser.requireId(), workspaceId, repositoryId, template);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", job.getStatus().name(),
                "jobId", job.getId().toString(),
                "repositoryId", repositoryId.toString(),
                "totalEntities", job.getTotalEntities(),
                "message", "Bulk generation queued. Poll /bulk-generation-jobs/"
                        + job.getId() + " for progress."));
    }

    /**
     * Poll the progress of a bulk generation run.
     */
    @GetMapping("/workspaces/{workspaceId}/bulk-generation-jobs/{jobId}")
    @Operation(summary = "Poll the status of a bulk documentation generation run")
    public Map<String, Object> bulkGenerationStatus(
            @PathVariable UUID workspaceId,
            @PathVariable UUID jobId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        BulkGenerationJob job = bulkGenerationService.find(jobId)
                .orElseThrow(() -> new NotFoundException("Bulk generation job not found"));
        if (!job.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Bulk generation job not found in this workspace");
        }
        return bulkGenerationService.toResponse(job);
    }

    /**
     * List the most recent bulk generation runs for a workspace.
     */
    @GetMapping("/workspaces/{workspaceId}/bulk-generation-jobs")
    @Operation(summary = "List recent bulk documentation generation runs")
    public List<Map<String, Object>> bulkGenerationJobs(
            @PathVariable UUID workspaceId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        return bulkGenerationService.recentForWorkspace(workspaceId).stream()
                .map(bulkGenerationService::toResponse)
                .toList();
    }
}