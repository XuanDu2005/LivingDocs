package com.livingdocs.modules.ai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.dto.PreviewGenerateResponse;
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

    public AiController(AiDocumentationService aiService,
                        DocumentVersionService versionService,
                        DriftAlertService driftService,
                        DocumentVersionRepository versionRepository,
                        WorkspaceService workspaceService,
                        ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.versionService = versionService;
        this.driftService = driftService;
        this.versionRepository = versionRepository;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/generate")
    @Operation(summary = "Generate documentation from source files and append as a new version")
    public DocumentVersionResponse generate(@PathVariable UUID workspaceId,
                                            @PathVariable UUID documentId,
                                            @RequestParam(required = false, defaultValue = "MODULE_GUIDE") String template,
                                            @RequestBody List<AiDtos.SourceFile> files) {
        var v = aiService.generateForDocument(CurrentUser.requireId(), documentId, files, template);
        return DocumentVersionResponse.from(v);
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
    public DocumentVersionResponse regenerate(@PathVariable UUID workspaceId,
                                              @PathVariable UUID documentId) {
        var v = aiService.regenerateOnDemand(CurrentUser.requireId(), workspaceId, documentId);
        return DocumentVersionResponse.from(v);
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
     * Creates new documents for undocumented code entities.
     */
    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/generate-all")
    @Operation(summary = "Generate documentation for all undocumented code entities in a repository")
    public Map<String, Object> generateAllForRepository(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(required = false, defaultValue = "MODULE_GUIDE") String template) {
        var result = aiService.generateForRepository(
                CurrentUser.requireId(), workspaceId, repositoryId, template);
        return Map.of(
                "generated", result.generated(),
                "skipped", result.skipped(),
                "errors", result.errors(),
                "documents", result.documents()
        );
    }
}