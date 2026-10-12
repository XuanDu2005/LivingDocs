package com.livingdocs.modules.document.controller;

import com.livingdocs.modules.document.dto.CreateTagRequest;
import com.livingdocs.modules.document.dto.DocumentTagResponse;
import com.livingdocs.modules.document.service.DocumentTagService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import com.livingdocs.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class DocumentTagController {

    private final DocumentTagService tagService;
    private final WorkspaceService workspaceService;

    public DocumentTagController(DocumentTagService tagService, WorkspaceService workspaceService) {
        this.tagService = tagService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/tags")
    public List<DocumentTagResponse> listWorkspaceTags(@PathVariable UUID workspaceId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        return tagService.listTags(workspaceId);
    }

    @PostMapping("/tags")
    public ResponseEntity<DocumentTagResponse> createTag(@PathVariable UUID workspaceId,
                                                         @Valid @RequestBody CreateTagRequest req) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        DocumentTagResponse res = tagService.createTag(workspaceId, req.name(), req.colorHex());
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }
    
    @GetMapping("/documents/{documentId}/tags")
    public List<DocumentTagResponse> getDocumentTags(@PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        return tagService.getTagsForDocument(documentId);
    }

    @PostMapping("/documents/{documentId}/tags/{tagId}")
    public ResponseEntity<Void> assignTag(@PathVariable UUID workspaceId,
                                          @PathVariable UUID documentId,
                                          @PathVariable UUID tagId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        tagService.assignTagToDocument(documentId, tagId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/documents/{documentId}/tags/{tagId}")
    public ResponseEntity<Void> removeTag(@PathVariable UUID workspaceId,
                                          @PathVariable UUID documentId,
                                          @PathVariable UUID tagId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);
        tagService.removeTagFromDocument(documentId, tagId);
        return ResponseEntity.noContent().build();
    }
}