package com.livingdocs.modules.link.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.link.dto.CodeDocumentLinkResponse;
import com.livingdocs.modules.link.service.CodeDocumentLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Links", description = "Document ↔ code entity links")
public class CodeDocumentLinkController {

    private final CodeDocumentLinkService linkService;

    public CodeDocumentLinkController(CodeDocumentLinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/links")
    @Operation(summary = "Link a code entity to a document")
    public ResponseEntity<CodeDocumentLinkResponse> link(@PathVariable UUID workspaceId,
                                                          @PathVariable UUID documentId,
                                                          @RequestParam UUID codeEntityId,
                                                          @RequestParam(required = false, defaultValue = "REFERENCE") String linkKind,
                                                          @RequestParam(required = false) Float confidence) {
        CodeDocumentLinkResponse out = linkService.link(
                CurrentUser.requireId(), documentId, codeEntityId, linkKind, confidence);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/links")
    @Operation(summary = "List all code links of a document")
    public List<CodeDocumentLinkResponse> list(@PathVariable UUID workspaceId,
                                               @PathVariable UUID documentId) {
        return linkService.linksForDocument(CurrentUser.requireId(), documentId);
    }

    @DeleteMapping("/workspaces/{workspaceId}/documents/{documentId}/links")
    @Operation(summary = "Remove all code links for a document")
    public ResponseEntity<Void> clear(@PathVariable UUID workspaceId,
                                       @PathVariable UUID documentId) {
        linkService.unlinkDocument(CurrentUser.requireId(), documentId);
        return ResponseEntity.noContent().build();
    }
}