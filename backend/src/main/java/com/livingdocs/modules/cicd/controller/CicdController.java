package com.livingdocs.modules.cicd.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.ai.settings.GovernancePolicyService;
import com.livingdocs.modules.cicd.service.GitHubActionsWorkflowGenerator;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoints that hand out generated CI/CD configuration snippets
 * (GitHub Actions workflow file).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "CI/CD", description = "Generated GitHub Actions configuration")
public class CicdController {

    private final RepositoryRepository repositoryRepository;
    private final WorkspaceService workspaceService;
    private final GovernancePolicyService governancePolicyService;
    private final String backendUrl;

    public CicdController(RepositoryRepository repositoryRepository,
                         WorkspaceService workspaceService,
                         GovernancePolicyService governancePolicyService,
                         @Value("${app.public-url:http://localhost:8080}") String backendUrl) {
        this.repositoryRepository = repositoryRepository;
        this.workspaceService = workspaceService;
        this.governancePolicyService = governancePolicyService;
        this.backendUrl = backendUrl;
    }

    /**
     * Generate the GitHub Actions workflow for a repository.
     *
     * <p>The generated workflow does not carry the merge policy: it asks
     * LivingDocs at run time, so the workspace's current policy always
     * applies and the file never has to be regenerated when the policy
     * changes. The effective policy is reported in the response headers
     * so the caller can see what the gate will enforce.
     */
    @GetMapping(value = "/workspaces/{workspaceId}/repositories/{repositoryId}/github-actions.yml",
            produces = "application/x-yaml")
    @Operation(summary = "Generate a GitHub Actions workflow YAML for LivingDocs drift detection")
    public ResponseEntity<String> generateActions(@PathVariable UUID workspaceId,
                                                  @PathVariable UUID repositoryId) {
        UUID actor = CurrentUser.requireId();
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        workspaceService.requireRole(actor, repo.getWorkspaceId(), WorkspaceRole.MANAGER);

        // The workflow must address the workspace the repository
        // actually belongs to — not the one in the URL. Otherwise a
        // manager of two workspaces could point a workflow at the
        // wrong one and get a gate that reads another workspace's
        // policy.
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new com.livingdocs.common.exception.ForbiddenException(
                    "This repository does not belong to the workspace in the request");
        }
        UUID effectiveWorkspaceId = repo.getWorkspaceId();

        String policy = governancePolicyService.mergePolicy(effectiveWorkspaceId);
        String yaml = GitHubActionsWorkflowGenerator.generate(
                backendUrl, effectiveWorkspaceId.toString(), repositoryId.toString());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-yaml"))
                .header("Content-Disposition",
                        "attachment; filename=\"livingdocs-drift.yml\"")
                .header("X-LivingDocs-Merge-Policy", policy)
                .body(yaml);
    }
}
