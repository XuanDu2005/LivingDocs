package com.livingdocs.modules.github.controller;

import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubCredentialService;
import com.livingdocs.modules.github.service.GithubSeedService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes files into a connected repository — for seeding test data.
 *
 * <p>This is <b>not</b> part of the product. It exists so a developer can
 * push a code change to a demo repository without leaving the platform:
 * the LivingDocs drift detector then has something real to detect.
 *
 * <p>Two things keep it out of production:
 *
 * <ul>
 *   <li>It only exists when {@code github.sandbox} is {@code true} — the
 *       same switch that enables the stubbed GitHub client for local
 *       development.</li>
 *   <li>It requires the {@code SCM_COMMIT} permission, like the
 *       ordinary "publish a document" flow.</li>
 * </ul>
 *
 * <p>The GitHub credential never leaves the backend: it is resolved and
 * validated by {@link GithubCredentialService}, exactly like every other
 * GitHub call the platform makes.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "github", name = "sandbox", havingValue = "true")
@Tag(name = "Github", description = "GitHub integration")
public class GithubSeedController {

    private final GithubSeedService seedService;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;

    public GithubSeedController(GithubSeedService seedService,
                               RepositoryRepository repositoryRepository,
                               WorkspaceService workspaceService,
                               PermissionService permissionService) {
        this.seedService = seedService;
        this.repositoryRepository = repositoryRepository;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
    }

    /**
     * Write a set of files into a repository on one branch, in a single
     * commit.
     *
     * <p>One commit for all the files is deliberate: the drift detector
     * compares two <em>revisions</em>, so the seeded change has to land
     * as a single revision to be comparable against the documentation
     * that was written before it.
     */
    @PostMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/seed-files")
    @Operation(summary = "Write files into a connected repository (sandbox only)")
    public ResponseEntity<Map<String, Object>> seedFiles(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody SeedFilesRequest req,
            Authentication auth) {
        UUID actorId = com.livingdocs.common.security.CurrentUser.requireId();
        workspaceService.requireMember(actorId, workspaceId);
        permissionService.requireAnyPermission(actorId, PermissionCodes.SCM_COMMIT);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new com.livingdocs.common.exception.NotFoundException(
                        "Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new com.livingdocs.common.exception.NotFoundException(
                    "Repository not found in this workspace");
        }

        List<GithubSeedService.SeedFile> files = req.files().stream()
                .map(f -> new GithubSeedService.SeedFile(f.path(), f.content()))
                .toList();

        GithubSeedService.SeedResult result = seedService.writeFiles(
                repo, req.branch(), req.commitMessage(), files);
        return ResponseEntity.ok(Map.of(
                "commitSha", result.commitSha(),
                "branch", result.branch(),
                "filesWritten", result.filesWritten(),
                "url", result.url()));
    }

    /**
     * The files to write.
     */
    public record SeedFilesRequest(List<SeedFileInput> files,
                                   String branch,
                                   String commitMessage) {
    }

    /**
     * One file to write.
     */
    public record SeedFileInput(String path, String content) {
    }
}
