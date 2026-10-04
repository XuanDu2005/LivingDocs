package com.livingdocs.modules.github.controller;

import com.livingdocs.modules.github.dto.ConnectRepositoryRequest;
import com.livingdocs.modules.github.dto.GithubRepositoryCatalogItem;
import com.livingdocs.modules.github.dto.GithubRepositoryListResponse;
import com.livingdocs.modules.github.dto.PullRequestResponse;
import com.livingdocs.modules.github.dto.RepositoryResponse;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.service.GithubIngestionService;
import com.livingdocs.modules.github.service.GithubRepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for browsing the user's GitHub repository catalog and
 * managing repositories attached to a workspace.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Repositories", description = "Workspace repository management")
public class GithubRepositoryController {

    private final GithubRepositoryService repositoryService;
    private final GithubIngestionService ingestionService;

    public GithubRepositoryController(GithubRepositoryService repositoryService,
                                      GithubIngestionService ingestionService) {
        this.repositoryService = repositoryService;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/github/catalog/repositories")
    @Operation(summary = "List repositories visible to the connected GitHub account")
    public GithubRepositoryListResponse catalog() {
        List<GithubRepositoryCatalogItem> items = repositoryService.listCatalog();
        return new GithubRepositoryListResponse(items);
    }

    @PostMapping("/workspaces/{workspaceId}/repositories")
    @Operation(summary = "Connect a repository from the GitHub catalog to a workspace")
    public ResponseEntity<RepositoryResponse> connect(@PathVariable UUID workspaceId,
                                                      @Valid @RequestBody ConnectRepositoryRequest req) {
        Repository repo = repositoryService.connect(workspaceId, req.githubId(), req.defaultBranch());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(repo));
    }

    @GetMapping("/workspaces/{workspaceId}/repositories")
    @Operation(summary = "List repositories connected to a workspace")
    public List<RepositoryResponse> list(@PathVariable UUID workspaceId) {
        return repositoryService.listForWorkspace(workspaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @DeleteMapping("/workspaces/{workspaceId}/repositories/{repositoryId}")
    @Operation(summary = "Disconnect a repository from a workspace")
    public ResponseEntity<Void> disconnect(@PathVariable UUID workspaceId,
                                           @PathVariable UUID repositoryId) {
        repositoryService.disconnect(workspaceId, repositoryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/sync")
    @Operation(summary = "Re-synchronize a connected repository")
    public ResponseEntity<RepositoryResponse> sync(@PathVariable UUID workspaceId,
                                                   @PathVariable UUID repositoryId) {
        Repository repo = repositoryService.sync(workspaceId, repositoryId);
        return ResponseEntity.ok(toResponse(repo));
    }

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/pulls/ingest")
    @Operation(summary = "Pull the list of pull requests from GitHub and store them locally")
    public List<PullRequestResponse> ingestPulls(@PathVariable UUID workspaceId,
                                                 @PathVariable UUID repositoryId,
                                                 @RequestParam(defaultValue = "all") String state) {
        List<PullRequest> prs = ingestionService.ingestPullRequests(repositoryId, state);
        return prs.stream().map(this::toPrResponse).toList();
    }

    @GetMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/pulls")
    @Operation(summary = "List pull requests stored locally for a repository")
    public List<PullRequestResponse> listPulls(@PathVariable UUID workspaceId,
                                               @PathVariable UUID repositoryId) {
        return ingestionService.listForRepository(repositoryId).stream()
                .map(this::toPrResponse)
                .toList();
    }

    private RepositoryResponse toResponse(Repository r) {
        return new RepositoryResponse(
                r.getId().toString(),
                r.getWorkspaceId().toString(),
                r.getGithubId(),
                r.getOwner(),
                r.getName(),
                r.getFullName(),
                r.getDefaultBranch(),
                r.getHtmlUrl(),
                r.getDescription(),
                r.isPrivate(),
                r.getStatus().name(),
                r.getConnectedBy().toString(),
                r.getConnectedAt() == null ? null : r.getConnectedAt().toString(),
                r.getLastSyncedAt() == null ? null : r.getLastSyncedAt().toString());
    }

    private PullRequestResponse toPrResponse(PullRequest p) {
        return new PullRequestResponse(
                p.getId().toString(),
                p.getRepositoryId().toString(),
                p.getGithubPrNumber(),
                p.getTitle(),
                p.getState().name(),
                p.getAuthorLogin(),
                p.getHeadBranch(),
                p.getBaseBranch(),
                p.getHeadSha(),
                p.getHtmlUrl(),
                p.isDraft(),
                p.getOpenedAt(),
                p.getUpdatedAt(),
                p.getClosedAt(),
                p.getMergedAt(),
                p.getLastIngestedAt());
    }
}