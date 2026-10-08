package com.livingdocs.modules.workspace.web;

import com.livingdocs.modules.workspace.dto.UpdateWorkspaceLanguageRequest;
import com.livingdocs.modules.workspace.dto.WorkspaceLanguageResponse;
import com.livingdocs.modules.workspace.service.WorkspaceLanguageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/languages")
@Tag(name = "Workspace Languages", description = "Per-workspace supported programming languages for AI")
public class WorkspaceLanguageController {

    private final WorkspaceLanguageService service;

    public WorkspaceLanguageController(WorkspaceLanguageService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List supported programming languages for this workspace")
    public ResponseEntity<List<WorkspaceLanguageResponse>> list(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(service.listForWorkspace(workspaceId));
    }

    @PutMapping
    @Operation(summary = "Enable/disable a language and set custom prompt (Manager only)")
    public ResponseEntity<WorkspaceLanguageResponse> update(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceLanguageRequest req) {
        return ResponseEntity.ok(service.updateLanguage(workspaceId, req));
    }
}
