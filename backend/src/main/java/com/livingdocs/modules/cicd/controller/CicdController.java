package com.livingdocs.modules.cicd.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.cicd.service.GitHubActionsWorkflowGenerator;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final String backendUrl;

    public CicdController(RepositoryRepository repositoryRepository,
                         WorkspaceService workspaceService,
                         @Value("${app.public-url:http://localhost:8080}") String backendUrl) {
        this.repositoryRepository = repositoryRepository;
        this.workspaceService = workspaceService;
        this.backendUrl = backendUrl;
    }

    @GetMapping(value = "/workspaces/{workspaceId}/repositories/{repositoryId}/github-actions.yml",
            produces = "application/x-yaml")
    @Operation(summary = "Generate a GitHub Actions workflow YAML for LivingDocs drift detection")
    public ResponseEntity<String> generateActions(@PathVariable UUID workspaceId,
                                                  @PathVariable UUID repositoryId,
                                                  @RequestParam(defaultValue = "true") boolean blockOnCritical) {
        UUID actor = CurrentUser.requireId();
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        workspaceService.requireRole(actor, repo.getWorkspaceId(),
                com.livingdocs.modules.workspace.model.WorkspaceRole.MANAGER);
        String yaml = GitHubActionsWorkflowGenerator.generate(
                backendUrl, workspaceId.toString(), repositoryId.toString(), blockOnCritical);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-yaml"))
                .header("Content-Disposition",
                        "attachment; filename=\"livingdocs-drift.yml\"")
                .body(yaml);
    }
}