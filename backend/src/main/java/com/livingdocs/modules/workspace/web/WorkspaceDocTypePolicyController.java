package com.livingdocs.modules.workspace.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceDocTypePolicyRequest;
import com.livingdocs.modules.workspace.dto.WorkspaceDocTypePolicyResponse;
import com.livingdocs.modules.workspace.service.WorkspaceDocTypePolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-workspace overrides of the doc-type workflow policy.
 *
 * <p>The platform holds the default policy for every documentation type
 * ({@code /admin/platform-settings/doc-type-policies}). These endpoints
 * let a workspace manager narrow or widen that policy for their own
 * workspace without asking the platform administrator to change a rule
 * that applies to every other workspace.
 *
 * <p>Reading the list requires {@code GOVERNANCE_VIEW}; changing a row
 * requires {@code GOVERNANCE_MANAGE} <em>and</em> the workspace
 * {@code MANAGER} role — the capability says "may administer the
 * governance policy", the workspace role says "of this workspace".
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/doc-type-policies")
@Tag(name = "Workspace · Doc-type policies",
        description = "Per-workspace override of the AUTO_APPLY / MANAGER_REVIEW workflow per documentation type")
public class WorkspaceDocTypePolicyController {

    private final WorkspaceDocTypePolicyService service;

    public WorkspaceDocTypePolicyController(WorkspaceDocTypePolicyService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission({"GOVERNANCE_VIEW"})
    @Operation(summary = "List the effective doc-type policies for this workspace")
    public ResponseEntity<List<WorkspaceDocTypePolicyResponse>> list(
            @PathVariable UUID workspaceId) {
        return ResponseEntity.ok(service.list(workspaceId, CurrentUser.requireId()));
    }

    @PutMapping("/{docType}")
    @RequirePermission({"GOVERNANCE_MANAGE"})
    @Operation(summary = "Set the workflow this workspace applies to one doc type (workspace Manager required)")
    public ResponseEntity<WorkspaceDocTypePolicyResponse> upsert(
            @PathVariable UUID workspaceId,
            @PathVariable String docType,
            @Valid @RequestBody UpdateWorkspaceDocTypePolicyRequest req) {
        return ResponseEntity.ok(service.upsert(
                workspaceId, CurrentUser.requireId(), docType, req));
    }

    @DeleteMapping("/{docType}")
    @RequirePermission({"GOVERNANCE_MANAGE"})
    @Operation(summary = "Drop the workspace override so the doc type inherits the platform policy again")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId,
                                       @PathVariable String docType) {
        service.delete(workspaceId, CurrentUser.requireId(), docType);
        return ResponseEntity.noContent().build();
    }
}
