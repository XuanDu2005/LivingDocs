package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Administrative operations that span multiple modules.
 *
 * <p>The current implementation re-uses the workspace role enum to model
 * platform-wide roles (DEVELOPER → MEMBER, STAFF / MANAGER / ADMIN →
 * MANAGER). Full RBAC with a dedicated platform role can be wired in
 * later without breaking these endpoints.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final AuditLogService auditLogService;

    public AdminService(UserRepository userRepository,
                        WorkspaceRepository workspaceRepository,
                        WorkspaceMemberRepository memberRepository,
                        AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public User updateRole(UUID actorId, UUID userId, String role) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ForbiddenException("Unknown actor"));
        if (!actor.isEnabled()) {
            throw new ForbiddenException("Account disabled");
        }
        WorkspaceRole newRole = parseRole(role);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        // For each workspace the target is a member of, apply the new role.
        List<WorkspaceMember> memberships = memberRepository.findAllByUserId(userId);
        for (WorkspaceMember m : memberships) {
            m.setRole(newRole);
        }
        auditLogService.record(actorId, "ADMIN", "user.role.update",
                "user", userId.toString(), null,
                java.util.Map.of("new_role", role));
        return target;
    }

    private WorkspaceRole parseRole(String raw) {
        if (raw == null) throw new BadRequestException("role is required");
        try {
            return WorkspaceRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown role: " + raw);
        }
    }
}