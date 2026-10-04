package com.livingdocs.modules.version.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.version.dto.DocumentVersionDiffResponse;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import com.livingdocs.modules.version.dto.DocumentVersionSummary;
import com.livingdocs.modules.version.service.DocumentVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for browsing the immutable version history of a document.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Versions", description = "Document version history / change log")
public class DocumentVersionController {

    private final DocumentVersionService versionService;

    public DocumentVersionController(DocumentVersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping("/documents/{documentId}/versions")
    @Operation(summary = "List all versions of a document (timeline)")
    public List<DocumentVersionSummary> timeline(@PathVariable UUID documentId) {
        return versionService.timeline(CurrentUser.requireId(), documentId);
    }

    @GetMapping("/documents/{documentId}/versions/{versionNumber}")
    @Operation(summary = "Fetch a specific version of a document")
    public DocumentVersionResponse get(@PathVariable UUID documentId,
                                       @PathVariable Integer versionNumber) {
        return versionService.get(CurrentUser.requireId(), documentId, versionNumber);
    }

    @GetMapping("/documents/{documentId}/diff")
    @Operation(summary = "Diff two versions of a document")
    public DocumentVersionDiffResponse diff(@PathVariable UUID documentId,
                                            @RequestParam("from") Integer from,
                                            @RequestParam("to") Integer to) {
        return versionService.diff(CurrentUser.requireId(), documentId, from, to);
    }

    @PostMapping("/documents/{documentId}/versions/{versionNumber}/publish")
    @Operation(summary = "Publish a specific approved version (Manager only)")
    public void publish(@PathVariable UUID documentId,
                        @PathVariable Integer versionNumber) {
        versionService.publishVersion(CurrentUser.requireId(), documentId, versionNumber);
    }

    @PostMapping("/documents/{documentId}/rollback")
    @Operation(summary = "Roll back a document to a previous version (Manager only)")
    public DocumentVersionResponse rollback(@PathVariable UUID documentId,
                                            @RequestParam("version") Integer version) {
        var v = versionService.rollback(CurrentUser.requireId(), documentId, version);
        return DocumentVersionResponse.from(v);
    }
}