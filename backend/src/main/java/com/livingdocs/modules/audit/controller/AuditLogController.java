package com.livingdocs.modules.audit.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/workspaces/{workspaceId}/audit-logs")
    @Operation(summary = "List audit logs for a workspace (Manager only)")
    public List<AuditLog> list(@PathVariable UUID workspaceId) {
        workspaceService.requireRole(CurrentUser.requireId(), workspaceId,
                com.livingdocs.modules.workspace.model.WorkspaceRole.MANAGER);
        return repository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }
}