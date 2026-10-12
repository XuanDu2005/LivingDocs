package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.AdminRepositoryResponse;
import com.livingdocs.modules.admin.service.AdminRepositoryService;
import com.livingdocs.modules.ai.indexing.IndexJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-wide repository administration.
 *
 * <p>Lets an admin browse every connected repository on the platform,
 * force a reindex, or unlink a repository without needing to be a
 * member of the workspace that owns it. The read endpoint bypasses
 * the per-workspace membership check; the write endpoints are gated
 * by {@code @RequirePlatformRole("ADMIN")}.
 */
@RestController
@RequestMapping("/api/v1/admin/repositories")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin · Repositories", description = "Cross-tenant repository browser and admin actions")
public class AdminRepositoryController {

    private final AdminRepositoryService adminRepositoryService;

    public AdminRepositoryController(AdminRepositoryService adminRepositoryService) {
        this.adminRepositoryService = adminRepositoryService;
    }

    @GetMapping
    @Operation(summary = "List every connected repository across all workspaces")
    public List<AdminRepositoryResponse> list() {
        return adminRepositoryService.listAll();
    }

    @PostMapping("/{repositoryId}/reindex")
    @Operation(summary = "Enqueue a full-workspace reindex for the workspace that owns this repository")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable("repositoryId") UUID repositoryId) {
        UUID actor = CurrentUser.requireId();
        IndexJob job = adminRepositoryService.forceReindex(repositoryId);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.getId().toString(),
                "status", job.getStatus().name(),
                "createdBy", actor.toString(),
                "workspaceId", job.getWorkspaceId().toString()
        ));
    }

    @DeleteMapping("/{repositoryId}")
    @Operation(summary = "Unlink a repository from its workspace")
    public ResponseEntity<Void> unlink(@PathVariable("repositoryId") UUID repositoryId) {
        adminRepositoryService.unlink(repositoryId);
        return ResponseEntity.noContent().build();
    }
}
