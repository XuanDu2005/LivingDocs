package com.livingdocs.modules.workspace.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceSettingsRequest;
import com.livingdocs.modules.workspace.dto.WorkspaceSettingsResponse;
import com.livingdocs.modules.workspace.service.WorkspaceSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/settings")
@Tag(name = "Workspace Settings", description = "Per-workspace governance settings")
public class WorkspaceSettingsController {

    private final WorkspaceSettingsService settingsService;

    public WorkspaceSettingsController(WorkspaceSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @Operation(summary = "Get workspace settings")
    public ResponseEntity<WorkspaceSettingsResponse> getSettings(@PathVariable UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        return ResponseEntity.ok(settingsService.getSettings(workspaceId, actor));
    }

    @PutMapping
    @Operation(summary = "Update workspace settings (Manager role required)")
    public ResponseEntity<WorkspaceSettingsResponse> updateSettings(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceSettingsRequest req) {
        UUID actor = CurrentUser.requireId();
        return ResponseEntity.ok(settingsService.updateSettings(workspaceId, actor, req));
    }
}
