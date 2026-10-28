package com.livingdocs.modules.ai.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.ai.settings.GovernancePolicyService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Exposes the governance configuration that the runtime actually enforces,
 * plus the merge gate that CI/CD consults.
 *
 * <p>Before this endpoint the policy knobs were only visible in the
 * settings screens; nothing told a user (or a CI job) what the
 * effective policy was for a given workspace. This makes the decision
 * inspectable: the same service that gates the generation flow answers
 * here.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Governance", description = "Effective documentation governance policy")
public class GovernanceController {

    private final GovernancePolicyService governancePolicyService;
    private final WorkspaceService workspaceService;

    public GovernanceController(GovernancePolicyService governancePolicyService,
                                 WorkspaceService workspaceService) {
        this.governancePolicyService = governancePolicyService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/workspaces/{workspaceId}/governance")
    @Operation(summary = "Show the governance policy that currently applies to this workspace")
    public Map<String, Object> policy(@PathVariable UUID workspaceId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", workspaceId.toString());
        out.put("requireManagerApproval", governancePolicyService.requiresManagerApproval(workspaceId));
        out.put("confidenceThreshold", governancePolicyService.confidenceThreshold(workspaceId));
        out.put("mergePolicy", governancePolicyService.mergePolicy(workspaceId));
        out.put("blocksOnCriticalDrift", governancePolicyService.blocksOnCriticalDrift(workspaceId));
        out.put("driftSeverityThreshold", governancePolicyService.driftSeverityThreshold(workspaceId).name());
        return out;
    }

    @GetMapping("/workspaces/{workspaceId}/governance/doc-types/{docType}")
    @Operation(summary = "Show the workflow that applies to one documentation type")
    public Map<String, Object> docTypePolicy(@PathVariable UUID workspaceId,
                                             @PathVariable String docType) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", workspaceId.toString());
        out.put("docType", docType);
        out.put("workflow", governancePolicyService.workflowFor(workspaceId, docType).name());
        out.put("autoApply", governancePolicyService.isAutoApply(workspaceId, docType));
        return out;
    }

    @GetMapping("/workspaces/{workspaceId}/governance/merge-gate")
    @Operation(summary = "Evaluate the merge gate for a pull request (used by CI/CD)")
    public Map<String, Object> mergeGate(@PathVariable UUID workspaceId,
                                         @RequestParam(required = false) UUID pullRequestId) {
        workspaceService.requireMember(CurrentUser.requireId(), workspaceId);

        var verdict = governancePolicyService.evaluateMergeGate(workspaceId, pullRequestId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", workspaceId.toString());
        out.put("pullRequestId", pullRequestId == null ? null : pullRequestId.toString());
        out.put("blocked", verdict.blocked());
        out.put("policy", verdict.policy());
        out.put("severityThreshold", verdict.severityThreshold().name());
        out.put("blockingAlertCount", verdict.blockingAlertCount());
        out.put("blockingAlertIds", verdict.blockingAlertIds().stream().map(UUID::toString).toList());
        out.put("reason", verdict.reason());
        return out;
    }
}
