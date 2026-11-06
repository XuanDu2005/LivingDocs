package com.livingdocs.modules.workspace.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.workspace.service.WorkspaceCiTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The CI token of a workspace.
 *
 * <p>The token authenticates a CI job that asks LivingDocs whether a
 * pull request may be merged. It is issued here, and only the manager
 * who issued it ever sees it: the platform stores its hash and cannot
 * read the token back.
 *
 * <p>Changing the token requires {@code GOVERNANCE_MANAGE} <em>and</em>
 * the workspace {@code MANAGER} role — the capability says "may
 * administer the governance policy", the workspace role says "of this
 * workspace". A token is a credential that lets a job act on the
 * workspace, so handing it out is a workspace-administration decision.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/ci-token")
@Tag(name = "Workspace · CI token",
        description = "The credential a CI job presents to enforce the merge gate")
public class WorkspaceCiTokenController {

    private final WorkspaceCiTokenService ciTokenService;

    public WorkspaceCiTokenController(WorkspaceCiTokenService ciTokenService) {
        this.ciTokenService = ciTokenService;
    }

    @GetMapping
    @RequirePermission({"GOVERNANCE_VIEW"})
    @Operation(summary = "Show whether this workspace has a CI token")
    public ResponseEntity<Map<String, Object>> status(@PathVariable UUID workspaceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspaceId.toString());
        body.put("hasToken", ciTokenService.hasToken(workspaceId));
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/rotate", produces = "application/json")
    @RequirePermission({"GOVERNANCE_MANAGE"})
    @Operation(summary = "Issue or rotate the CI token (workspace Manager required)")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable UUID workspaceId) {
        var issued = ciTokenService.issue(CurrentUser.requireId(), workspaceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspaceId.toString());
        // The plaintext is shown exactly once. The platform stores only
        // its hash, so it cannot be retrieved later.
        body.put("token", issued.token());
        body.put("issuedAt", issued.issuedAt().toString());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping
    @RequirePermission({"GOVERNANCE_MANAGE"})
    @Operation(summary = "Revoke the CI token (workspace Manager required)")
    public ResponseEntity<Void> revoke(@PathVariable UUID workspaceId) {
        ciTokenService.revoke(CurrentUser.requireId(), workspaceId);
        return ResponseEntity.noContent().build();
    }
}
