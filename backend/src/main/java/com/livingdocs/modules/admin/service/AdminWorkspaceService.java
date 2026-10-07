package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.AdminWorkspaceResponse;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrator-only operations on workspaces. The platform-level
 * {@code ADMIN} guard is applied at the controller boundary; this
 * service is only concerned with the cross-tenant queries and the
 * soft-delete semantics that platform administrators are entitled to
 * (bypassing the owner-only check that {@code WorkspaceService.delete}
 * enforces for the creator).
 */
@Service
public class AdminWorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final AuditLogService auditLogService;

    public AdminWorkspaceService(WorkspaceRepository workspaceRepository,
                                 WorkspaceMemberRepository memberRepository,
                                 AuditLogService auditLogService) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<AdminWorkspaceResponse> listAll() {
        return workspaceRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminWorkspaceResponse getOne(UUID workspaceId) {
        Workspace ws = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
        return toResponse(ws);
    }

    @Transactional
    public void delete(UUID actorId, UUID workspaceId) {
        Workspace ws = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
        long members = memberRepository.countByWorkspaceId(workspaceId);
        memberRepository.findAllByWorkspaceId(workspaceId)
                .forEach(memberRepository::delete);
        workspaceRepository.delete(ws);
        auditLogService.record(actorId, "ADMIN", "workspace.admin.delete",
                "workspace", workspaceId.toString(), workspaceId,
                Map.of("name", ws.getName(), "membersRemoved", members));
    }

    private AdminWorkspaceResponse toResponse(Workspace ws) {
        long total = memberRepository.countByWorkspaceId(ws.getId());
        long managers = memberRepository.countByWorkspaceIdAndRole(ws.getId(), WorkspaceRole.MANAGER);
        return AdminWorkspaceResponse.from(ws, total, managers);
    }
}