package com.livingdocs.modules.document.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.document.dto.CreateDocumentRequest;
import com.livingdocs.modules.document.dto.CreateVersionRequest;
import com.livingdocs.modules.document.dto.DocumentResponse;
import com.livingdocs.modules.document.dto.UpdateDocumentRequest;
import com.livingdocs.modules.document.service.DocumentService;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.service.DocumentVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for documents and their version history.
 *
 * <p>Documents are the unit of review. Body edits go through a separate
 * version endpoint so every change is captured in the change log.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Documents", description = "Document CRUD + version history")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentVersionService versionService;

    public DocumentController(DocumentService documentService,
                              DocumentVersionService versionService) {
        this.documentService = documentService;
        this.versionService = versionService;
    }

    @GetMapping("/workspaces/{workspaceId}/documents")
    @Operation(summary = "List documents in a workspace (optionally filtered by docType)")
    public List<DocumentResponse> list(@PathVariable UUID workspaceId,
                                       @RequestParam(required = false) String docType) {
        return documentService.list(CurrentUser.requireId(), workspaceId, docType);
    }

    @PostMapping("/workspaces/{workspaceId}/documents")
    @Operation(summary = "Create a new document in a workspace")
    public ResponseEntity<DocumentResponse> create(@PathVariable UUID workspaceId,
                                                   @Valid @RequestBody CreateDocumentRequest req) {
        DocumentResponse out = documentService.create(CurrentUser.requireId(), workspaceId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}")
    @Operation(summary = "Fetch a single document")
    public DocumentResponse get(@PathVariable UUID workspaceId,
                                @PathVariable UUID documentId) {
        return documentService.get(CurrentUser.requireId(), workspaceId, documentId);
    }

    @PutMapping("/workspaces/{workspaceId}/documents/{documentId}")
    @Operation(summary = "Update a document's metadata")
    public DocumentResponse update(@PathVariable UUID workspaceId,
                                   @PathVariable UUID documentId,
                                   @Valid @RequestBody UpdateDocumentRequest req) {
        return documentService.update(CurrentUser.requireId(), workspaceId, documentId, req);
    }

    @DeleteMapping("/workspaces/{workspaceId}/documents/{documentId}")
    @Operation(summary = "Delete a document (Manager only)")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId,
                                       @PathVariable UUID documentId) {
        documentService.delete(CurrentUser.requireId(), workspaceId, documentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Convenience endpoint to push a new version of the body authored by a
     * human. Body changes are otherwise managed through the dedicated
     * {@code /documents/{documentId}/versions} routes on the version
     * controller.
     */
    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/versions")
    @Operation(summary = "Append a new human-edited version of a document")
    public ResponseEntity<DocumentVersionResponse> appendVersion(@PathVariable UUID workspaceId,
                                                                 @PathVariable UUID documentId,
                                                                 @Valid @RequestBody CreateVersionRequest req) {
        var v = versionService.createHumanVersion(
                CurrentUser.requireId(), documentId,
                req.bodyMarkdown(), req.changeSummary(), ActorRole.STAFF);
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentVersionResponse.from(v));
    }
}