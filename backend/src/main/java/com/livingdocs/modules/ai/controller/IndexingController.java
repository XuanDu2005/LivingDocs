package com.livingdocs.modules.ai.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.ai.indexing.IndexJob;
import com.livingdocs.modules.ai.indexing.IndexingService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read/manage endpoints for {@link IndexJob} rows.
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@code GET /workspaces/{id}/indexing-jobs} — paginated list of
 *       recent jobs for the dashboard.</li>
 *   <li>{@code GET /workspaces/{id}/indexing-jobs/{jobId}} — single job
 *       for polling status.</li>
 *   <li>{@code POST /workspaces/{id}/knowledge/reindex} — fire a
 *       full-workspace reindex job.</li>
 *   <li>{@code POST /indexing-jobs/{jobId}/cancel} — cancel a pending
 *       or running job.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Indexing Jobs", description = "Track asynchronous knowledge-base indexing jobs")
public class IndexingController {

    private final IndexingService indexingService;
    private final WorkspaceService workspaceService;

    public IndexingController(IndexingService indexingService,
                              WorkspaceService workspaceService) {
        this.indexingService = indexingService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/workspaces/{workspaceId}/indexing-jobs")
    @Operation(summary = "List recent indexing jobs for a workspace")
    public Map<String, Object> listForWorkspace(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID actorId = CurrentUser.requireId();
        workspaceService.requireMember(actorId, workspaceId);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<IndexJob> p = indexingService.listForWorkspace(workspaceId, pageable);
        return Map.of(
                "items", p.getContent(),
                "page", p.getNumber(),
                "size", p.getSize(),
                "total", p.getTotalElements());
    }

    @GetMapping("/workspaces/{workspaceId}/indexing-jobs/recent")
    @Operation(summary = "Return the 50 most recent jobs across a workspace")
    public List<IndexJob> recent(@PathVariable UUID workspaceId) {
        UUID actorId = CurrentUser.requireId();
        workspaceService.requireMember(actorId, workspaceId);
        return indexingService.recentForWorkspace(workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/indexing-jobs/{jobId}")
    @Operation(summary = "Get a single indexing job by id")
    public ResponseEntity<IndexJob> get(@PathVariable UUID workspaceId,
                                        @PathVariable UUID jobId) {
        UUID actorId = CurrentUser.requireId();
        workspaceService.requireMember(actorId, workspaceId);
        return indexingService.find(workspaceId, jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/workspaces/{workspaceId}/knowledge/reindex")
    @Operation(summary = "Enqueue a full-workspace reindex job")
    public ResponseEntity<IndexJob> reindex(@PathVariable UUID workspaceId) {
        UUID actorId = CurrentUser.requireId();
        workspaceService.requireMember(actorId, workspaceId);
        IndexJob job = indexingService.enqueueReindexAll(workspaceId, actorId);
        return ResponseEntity.accepted().body(job);
    }

    @PostMapping("/indexing-jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a pending or running indexing job")
    public IndexJob cancel(@PathVariable UUID jobId) {
        UUID actorId = CurrentUser.requireId();
        return indexingService.cancel(jobId, actorId);
    }
}