package com.livingdocs.modules.audit.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for browsing the audit trail.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Audit", description = "Append-only audit log of governance actions")
public class AuditLogController {

    private final AuditLogRepository repository;
    private final WorkspaceService workspaceService;

    public AuditLogController(AuditLogRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    /**
     * List the audit trail of a workspace.
     *
     * <p>Paging is supported but optional: without {@code page}/{@code size}
     * the endpoint returns the most recent {@link #DEFAULT_LIMIT} entries
     * so a large audit table can never be loaded in one unbounded
     * query. Callers that need everything should follow the
     * {@code totalPages} cursor.
     */
    @GetMapping("/workspaces/{workspaceId}/audit-logs")
    @Operation(summary = "List audit logs for a workspace (Manager only)")
    public PageResponse list(@PathVariable UUID workspaceId,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "200") int size) {
        workspaceService.requireRole(CurrentUser.requireId(), workspaceId,
                com.livingdocs.modules.workspace.model.WorkspaceRole.MANAGER);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), DEFAULT_LIMIT);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = repository.findAllByWorkspaceId(workspaceId, pageable);
        return new PageResponse(result.getContent(), safePage, safeSize, result.getTotalElements());
    }

    /** Upper bound on the number of audit rows returned in one response. */
    public static final int DEFAULT_LIMIT = 1000;

    public record PageResponse(List<AuditLog> items, int page, int size, long total) {}
}