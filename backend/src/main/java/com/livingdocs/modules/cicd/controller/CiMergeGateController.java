package com.livingdocs.modules.cicd.controller;

import com.livingdocs.common.security.CiTokenAuthenticationFilter;
import com.livingdocs.modules.cicd.service.MergeGateService;
import com.livingdocs.modules.cicd.service.MergeGateService.MergeGateVerdict;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The endpoint a CI job calls to ask LivingDocs whether a pull request
 * may be merged.
 *
 * <p>This is the enforcement point of the merge gate. The generated
 * GitHub Actions workflow calls it on every pull request and fails the
 * job when the answer is "no".
 *
 * <h2>Authentication</h2>
 *
 * <p>Unlike the rest of the API, this endpoint is not called with a
 * user JWT — a workflow has no user session. It is called with the
 * workspace's CI token, presented in the
 * {@code X-LivingDocs-CI-Token} header. The token resolves to exactly
 * one workspace, and the request may only act on that workspace: the
 * workspace in the path has to be the one the token authenticates.
 */
@RestController
@RequestMapping("/api/v1/cicd")
@Tag(name = "CI/CD", description = "Endpoints a CI job calls to enforce the merge gate")
public class CiMergeGateController {

    private final MergeGateService mergeGateService;

    public CiMergeGateController(MergeGateService mergeGateService) {
        this.mergeGateService = mergeGateService;
    }

    @GetMapping(value = "/workspaces/{workspaceId}/repositories/{repositoryId}/merge-gate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Ask LivingDocs whether a pull request may be merged "
            + "(authenticated with the workspace CI token)")
    public ResponseEntity<Map<String, Object>> mergeGate(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam long pullRequestNumber,
            HttpServletRequest request) {

        UUID authenticated = CiTokenAuthenticationFilter.authenticatedWorkspace(request);
        if (authenticated == null) {
            // A missing or unknown token is a 401, not a silent pass.
            // The CI job must never be able to skip the gate by
            // forgetting its credential.
            return ResponseEntity.status(401).body(Map.of(
                    "error", "UNAUTHORIZED",
                    "message", "Missing or invalid CI token. Present the workspace CI token "
                            + "in the " + CiTokenAuthenticationFilter.CI_TOKEN_HEADER + " header."));
        }
        if (!authenticated.equals(workspaceId)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "FORBIDDEN",
                    "message", "The CI token does not authenticate this workspace."));
        }

        MergeGateVerdict verdict = mergeGateService.evaluate(
                workspaceId, repositoryId, pullRequestNumber);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", verdict.workspaceId().toString());
        body.put("repositoryId", verdict.repositoryId().toString());
        body.put("pullRequestNumber", verdict.pullRequestNumber());
        body.put("pullRequestId",
                verdict.pullRequestId() == null ? null : verdict.pullRequestId().toString());
        body.put("blocked", verdict.blocked());
        body.put("policy", verdict.policy());
        body.put("severityThreshold", verdict.severityThreshold());
        body.put("blockingAlertCount", verdict.blockingAlertCount());
        body.put("blockingAlertIds", verdict.blockingAlertIds().stream().map(UUID::toString).toList());
        body.put("alertsFiled", verdict.alertsFiled());
        body.put("scanNote", verdict.scanNote());
        body.put("reason", verdict.reason());
        return ResponseEntity.ok(body);
    }
}
