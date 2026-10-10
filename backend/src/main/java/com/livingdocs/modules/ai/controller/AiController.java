package com.livingdocs.modules.ai.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.drift.dto.DriftAlertResponse;
import com.livingdocs.modules.drift.service.DriftAlertService;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import com.livingdocs.modules.version.service.DocumentVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public AiController(AiDocumentationService aiService,
                        DocumentVersionService versionService,
                        DriftAlertService driftService) {
        this.aiService = aiService;
        this.versionService = versionService;
        this.driftService = driftService;
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

    /** Convenience wrapper for the request body. */
    public record DriftDetectionBody(
            List<AiDtos.SourceFile> filesBefore,
            List<AiDtos.SourceFile> filesAfter) {}
}