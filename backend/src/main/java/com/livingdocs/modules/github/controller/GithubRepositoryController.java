package com.livingdocs.modules.github.controller;

import com.livingdocs.modules.github.dto.CommitDocumentRequest;
import com.livingdocs.modules.github.dto.CommitDocumentResponse;
import com.livingdocs.modules.github.dto.CommitDto;
import com.livingdocs.modules.github.dto.ConnectRepositoryRequest;
import com.livingdocs.modules.github.dto.GithubRepositoryCatalogItem;
import com.livingdocs.modules.github.dto.GithubRepositoryListResponse;
import com.livingdocs.modules.github.dto.PullRequestResponse;
import com.livingdocs.modules.github.dto.RepositoryResponse;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.service.CommitIngestionService;
import com.livingdocs.modules.github.service.DocSyncService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
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
    private final CommitIngestionService commitIngestionService;
    private final DocSyncService docSyncService;

    public GithubRepositoryController(GithubRepositoryService repositoryService,
                                      GithubIngestionService ingestionService,
                                      CommitIngestionService commitIngestionService,
                                      DocSyncService docSyncService) {
        this.repositoryService = repositoryService;
        this.ingestionService = ingestionService;
        this.commitIngestionService = commitIngestionService;
        this.docSyncService = docSyncService;
    }

    @GetMapping("/github/catalog/repositories")
    @Operation(summary = "List repositories visible to the connected GitHub account")
    public GithubRepositoryListResponse catalog() {
        List<GithubRepositoryCatalogItem> items = repositoryService.listCatalog();
        return new GithubRepositoryListResponse(items);
    }

    @GetMapping("/github/repositories/by-url")
    @Operation(summary = "Fetch a single repository by its GitHub URL")
    public GithubRepositoryCatalogItem getByUrl(@RequestParam String url) {
        return repositoryService.getRepositoryByUrl(url);
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

    @PutMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/auto-update")
    @Operation(summary = "Enable or disable AI auto-update for a specific repository")
    public ResponseEntity<RepositoryResponse> setRepositoryAutoUpdate(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam boolean enabled) {
        Repository repo = repositoryService.setAutoUpdate(workspaceId, repositoryId, enabled);
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

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/commits/ingest")
    @Operation(summary = "Pull the latest commits from GitHub and store them locally")
    public Map<String, Object> ingestCommits(@PathVariable UUID workspaceId,
                                              @PathVariable UUID repositoryId,
                                              @RequestParam(defaultValue = "30") int perPage) {
        var stored = commitIngestionService.ingestLatest(workspaceId, repositoryId, perPage);
        return Map.of("ingested", stored.size(), "commits", stored.stream().map(CommitDto::from).toList());
    }

    @GetMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/commits")
    @Operation(summary = "List commits stored locally for a repository")
    public List<CommitDto> listCommits(@PathVariable UUID workspaceId,
                                       @PathVariable UUID repositoryId,
                                       @RequestParam(defaultValue = "50") int limit) {
        return commitIngestionService.listForRepository(workspaceId, repositoryId, limit).stream()
                .map(CommitDto::from)
                .toList();
    }

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/commit")
    @Operation(summary = "Publish a document to a repository as a pull request (creates a new branch, writes the file, and opens a PR)")
    public ResponseEntity<CommitDocumentResponse> commitDocument(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody CommitDocumentRequest req) {
        CommitDocumentResponse res = repositoryService.commitDocument(
                workspaceId, repositoryId,
                req.filePath(), req.content(),
                req.commitMessage(), req.prTitle(), req.prBody(), req.headBranch());
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/scan-docs")
    @GetMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/scan-docs")
    @Operation(summary = "Scan repository for documentation files and code references")
    public Map<String, Object> scanDocs(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) String branch) {
        // Get the authenticated user's access token from security context
        UUID actorId = com.livingdocs.common.security.CurrentUser.requireId();
        String accessToken = repositoryService.getAccessTokenForWorkspace(workspaceId);
        
        DocSyncService.ScanResult result = docSyncService.scanRepository(
                actorId, workspaceId, repositoryId, accessToken, branch);
        
        int driftCount = (int) result.files().stream().filter(f -> f.hasDrift()).count();
        
        return Map.of(
                "totalFiles", result.totalFiles(),
                "referencedEntities", result.referencedEntities(),
                "updatedEntities", result.updatedEntities(),
                "driftCount", driftCount,
                "files", result.files().stream()
                        .map(f -> Map.of(
                                "path", f.path(),
                                "type", f.type(),
                                "size", f.size() != null ? f.size() : 0,
                                "referencedEntities", f.referencedEntities(),
                                "foundEntities", f.foundEntities(),
                                "missingEntities", f.missingEntities(),
                                "hasDrift", f.hasDrift(),
                                "driftReason", f.driftReason() != null ? f.driftReason() : ""
                        ))
                        .toList()
        );
    }

    @GetMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/docs/{path:.*}")
    @Operation(summary = "Get content of a documentation file")
    public ResponseEntity<Map<String, Object>> getDocContent(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @PathVariable String path,
            @RequestParam(required = false) String branch) {
        UUID actorId = com.livingdocs.common.security.CurrentUser.requireId();
        String accessToken = repositoryService.getAccessTokenForWorkspace(workspaceId);

        String content = docSyncService.getFileContent(
                actorId, workspaceId, repositoryId, accessToken, path, branch);

        if (content == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "path", path,
                "content", content
        ));
    }

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/doc-sync/preview")
    @Operation(summary = "Preview documentation sync - generates diff between current and proposed content")
    public ResponseEntity<Map<String, Object>> previewDocSync(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestBody DocSyncRequest req) {
        UUID actorId = com.livingdocs.common.security.CurrentUser.requireId();
        String accessToken = repositoryService.getAccessTokenForWorkspace(workspaceId);

        DocSyncService.SyncPreviewResult result = docSyncService.previewSync(
                actorId, workspaceId, repositoryId, accessToken, req.filePath(), req.branch());

        // Build a mutable map because some fields may be null (e.g. proposedContent
        // when no code entities are found for this file).
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("filePath", result.filePath());
        body.put("currentContent", result.currentContent());
        body.put("proposedContent", result.proposedContent());
        body.put("diff", result.diff());
        body.put("findings", result.findings().stream()
                .map(f -> {
                    java.util.LinkedHashMap<String, Object> fm = new java.util.LinkedHashMap<>();
                    fm.put("driftKind", f.driftKind());
                    fm.put("severity", f.severity());
                    fm.put("title", f.title());
                    fm.put("description", f.description());
                    fm.put("suggestion", f.suggestion());
                    return fm;
                })
                .toList());
        body.put("noEntitiesFound", result.noEntitiesFound());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/doc-sync/queue")
    @Operation(summary = "Queue a doc update for review - creates a PENDING_REVIEW DocUpdateRequest instead of opening a PR")
    public ResponseEntity<Map<String, Object>> queueDocSync(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestBody DocSyncRequest req) {
        UUID actorId = com.livingdocs.common.security.CurrentUser.requireId();
        String accessToken = repositoryService.getAccessTokenForWorkspace(workspaceId);

        com.livingdocs.modules.docsync.dto.DocUpdateRequestDto queued =
                docSyncService.queueForReview(actorId, workspaceId, repositoryId, accessToken,
                        req.filePath(), req.branch());

        if (queued == null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "queued", false,
                    "message", "No changes detected — documentation already matches the code."
            ));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "queued", true,
                "request", queued,
                "message", "Queued for review. A manager can approve and push to GitHub."
        ));
    }

    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/doc-sync/apply")
    @Operation(summary = "Apply documentation sync - creates a PR with the updated content")
    public ResponseEntity<Map<String, Object>> applyDocSync(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestBody ApplyDocSyncRequest req) {
        UUID actorId = com.livingdocs.common.security.CurrentUser.requireId();
        String accessToken = repositoryService.getAccessTokenForWorkspace(workspaceId);

        String prUrl = docSyncService.applySync(
                actorId, workspaceId, repositoryId, accessToken,
                req.filePath(), req.proposedContent(), req.branch());

        if (prUrl == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to create pull request"));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "prUrl", prUrl,
                "message", "Pull request created successfully"
        ));
    }

    // Request/Response DTOs

    public record DocSyncRequest(String filePath, String branch) {}
    public record ApplyDocSyncRequest(String filePath, String proposedContent, String branch) {}

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
                r.getLastSyncedAt() == null ? null : r.getLastSyncedAt().toString(),
                r.isAutoUpdateEnabled());
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