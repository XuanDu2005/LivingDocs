package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.AdminWorkspaceMemberResponse;
import com.livingdocs.modules.admin.dto.AdminWorkspaceResponse;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.dto.AddMemberRequest;
import com.livingdocs.modules.workspace.dto.UpdateMemberRoleRequest;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Administrator-only operations on workspaces. The platform-level
 * {@code ADMIN} guard is applied at the controller boundary; this
 * service is only concerned with the cross-tenant queries and the
 * soft-delete semantics that platform administrators are entitled to
 * (bypassing the owner-only check that {@code WorkspaceService.delete}
 * enforces for the creator).
 *
 * <p>The membership operations delegate to
 * {@link WorkspaceService#addMemberAsAdmin},
 * {@link WorkspaceService#updateMemberRoleAsAdmin} and
 * {@link WorkspaceService#removeMemberAsAdmin} rather than touching
 * the membership table directly. Those variants drop the
 * {@code MANAGER}-of-this-workspace requirement — an administrator
 * is not required to be a member of the workspace they administer —
 * but they keep every domain rule: the user has to exist, a user
 * cannot be a member twice, and the workspace owner cannot be
 * demoted below {@code MANAGER} or removed.
 */
@Service
public class AdminWorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AdminWorkspaceService(WorkspaceRepository workspaceRepository,
                                 WorkspaceMemberRepository memberRepository,
                                 WorkspaceService workspaceService,
                                 UserRepository userRepository,
                                 AuditLogService auditLogService) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
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

    // -----------------------------------------------------------------
    // Membership
    // -----------------------------------------------------------------

    /**
     * List the members of a workspace across the tenant boundary.
     *
     * <p>The user's email and display name are resolved in one pass
     * rather than one query per member: a workspace with a hundred
     * members would otherwise cost a hundred round-trips.
     */
    @Transactional(readOnly = true)
    public List<AdminWorkspaceMemberResponse> listMembers(UUID workspaceId) {
        Workspace ws = requireWorkspace(workspaceId);
        List<WorkspaceMember> members = memberRepository.findAllByWorkspaceId(workspaceId);
        Map<UUID, User> users = usersById(members);

        return members.stream()
                .map(m -> toMemberResponse(m, users, ws.getOwnerId()))
                .toList();
    }

    /**
     * Add a member to a workspace on behalf of the platform.
     */
    @Transactional
    public AdminWorkspaceMemberResponse addMember(UUID actorId, UUID workspaceId, AddMemberRequest req) {
        Workspace ws = requireWorkspace(workspaceId);
        WorkspaceMember member = workspaceService.addMemberAsAdmin(workspaceId, req);

        auditLogService.record(actorId, "ADMIN", "workspace.member.add",
                "workspace_member", member.getId().toString(), workspaceId,
                Map.of(
                        "userId", member.getUserId().toString(),
                        "role", member.getRole().name(),
                        "workspace", ws.getName()
                ));

        return toMemberResponse(member, usersById(List.of(member)), ws.getOwnerId());
    }

    /**
     * Change a member's workspace role on behalf of the platform.
     */
    @Transactional
    public AdminWorkspaceMemberResponse updateMemberRole(UUID actorId, UUID workspaceId, UUID userId,
                                                         UpdateMemberRoleRequest req) {
        Workspace ws = requireWorkspace(workspaceId);
        WorkspaceRole previous = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(WorkspaceMember::getRole)
                .orElse(null);

        WorkspaceMember member = workspaceService.updateMemberRoleAsAdmin(workspaceId, userId, req);

        auditLogService.record(actorId, "ADMIN", "workspace.member.role.change",
                "workspace_member", member.getId().toString(), workspaceId,
                Map.of(
                        "userId", userId.toString(),
                        "previousRole", previous == null ? "NONE" : previous.name(),
                        "role", member.getRole().name(),
                        "workspace", ws.getName()
                ));

        return toMemberResponse(member, usersById(List.of(member)), ws.getOwnerId());
    }

    /**
     * Remove a member from a workspace on behalf of the platform.
     */
    @Transactional
    public void removeMember(UUID actorId, UUID workspaceId, UUID userId) {
        Workspace ws = requireWorkspace(workspaceId);
        WorkspaceRole previous = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(WorkspaceMember::getRole)
                .orElse(null);

        workspaceService.removeMemberAsAdmin(workspaceId, userId);

        auditLogService.record(actorId, "ADMIN", "workspace.member.remove",
                "workspace_member", userId.toString(), workspaceId,
                Map.of(
                        "userId", userId.toString(),
                        "previousRole", previous == null ? "NONE" : previous.name(),
                        "workspace", ws.getName()
                ));
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Workspace requireWorkspace(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
    }

    /**
     * Resolve the given members' users and index them by id.
     *
     * <p>The users are fetched with one query for the whole set, not
     * one query per member: a workspace with a hundred members would
     * otherwise cost a hundred round-trips.
     */
    private Map<UUID, User> usersById(List<WorkspaceMember> members) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (WorkspaceMember m : members) {
            userIds.add(m.getUserId());
        }
        Map<UUID, User> byId = new HashMap<>();
        if (userIds.isEmpty()) {
            return byId;
        }
        for (User u : userRepository.findAllById(userIds)) {
            byId.put(u.getId(), u);
        }
        return byId;
    }

    private AdminWorkspaceMemberResponse toMemberResponse(WorkspaceMember m,
                                                           Map<UUID, User> users,
                                                           UUID workspaceOwnerId) {
        User user = users.get(m.getUserId());
        return AdminWorkspaceMemberResponse.of(
                m,
                user != null ? user.getEmail() : null,
                user != null ? user.getDisplayName() : null,
                workspaceOwnerId);
    }

    private AdminWorkspaceResponse toResponse(Workspace ws) {
        long total = memberRepository.countByWorkspaceId(ws.getId());
        long managers = memberRepository.countByWorkspaceIdAndRole(ws.getId(), WorkspaceRole.MANAGER);
        return AdminWorkspaceResponse.from(ws, total, managers);
    }
}
