package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.RoleResponse;
import com.livingdocs.modules.admin.dto.UserWithRolesResponse;
import com.livingdocs.modules.admin.model.Role;
import com.livingdocs.modules.admin.model.UserRoleAssignment;
import com.livingdocs.modules.admin.repository.RoleRepository;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the platform role catalogue and per-user role assignments.
 *
 * <p>Roles are seeded by Flyway V6 and treated as immutable identifiers —
 * application code refers to them by {@link Role#getCode()} (e.g. ADMIN,
 * STAFF, MANAGER, TECHNICAL_LEAD, DEVELOPER). The catalogue itself is
 * editable (name/description/displayOrder) but new role codes require a
 * new migration so JWTs and audit payloads stay stable.
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public RoleService(RoleRepository roleRepository,
                       UserRoleAssignmentRepository assignmentRepository,
                       UserRepository userRepository,
                       AuditLogService auditLogService) {
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    // -------- catalogue --------

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAllByOrderByDisplayOrderAscNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RoleResponse updateRoleMetadata(UUID roleId,
                                           String name,
                                           String description,
                                           Integer displayOrder) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role not found"));
        if (name != null && !name.isBlank()) {
            role.setName(name.trim());
        }
        if (description != null) {
            role.setDescription(description.trim().isEmpty() ? null : description.trim());
        }
        if (displayOrder != null) {
            role.setDisplayOrder(displayOrder);
        }
        return toResponse(role);
    }

    // -------- assignments --------

    /**
     * Replace the active role set for {@code userId} with {@code roleCodes}.
     * Roles not in the new set are revoked; roles in the new set that are
     * missing are granted. Records an audit entry summarising the diff.
     */
    @Transactional
    public List<String> replaceRoles(UUID actorId, UUID userId, List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new ConflictException("Cannot remove all roles; user would lose access. Disable the account instead.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        Set<String> normalized = new HashSet<>();
        for (String code : roleCodes) {
            normalized.add(code.trim().toUpperCase(Locale.ROOT));
        }

        // Resolve all requested roles up-front.
        List<Role> requested = normalized.stream()
                .map(code -> roleRepository.findByCode(code)
                        .orElseThrow(() -> new NotFoundException("Unknown role code: " + code)))
                .toList();

        Set<UUID> requestedIds = new HashSet<>();
        for (Role r : requested) {
            requestedIds.add(r.getId());
        }

        List<UserRoleAssignment> current = assignmentRepository.findActiveByUserId(userId);
        Set<UUID> currentIds = new HashSet<>();
        for (UserRoleAssignment a : current) {
            currentIds.add(a.getRoleId());
        }

        // Revoke roles that are no longer requested.
        for (UserRoleAssignment existing : current) {
            if (!requestedIds.contains(existing.getRoleId())) {
                existing.revoke();
            }
        }
        // Grant roles that are new.
        for (Role role : requested) {
            if (!currentIds.contains(role.getId())) {
                assignmentRepository.save(new UserRoleAssignment(userId, role.getId(), actorId));
            }
        }

        // Build diff metadata for the audit entry.
        Set<String> previousCodes = new HashSet<>();
        for (UserRoleAssignment a : current) {
            Role r = roleRepository.findById(a.getRoleId()).orElse(null);
            if (r != null) previousCodes.add(r.getCode());
        }
        Set<String> granted = new HashSet<>(normalized);
        granted.removeAll(previousCodes);
        Set<String> revoked = new HashSet<>(previousCodes);
        revoked.removeAll(normalized);

        auditLogService.record(actorId, "ADMIN", "user.roles.replace",
                "user", userId.toString(), null,
                Map.of(
                        "granted", granted,
                        "revoked", revoked,
                        "now", normalized
                ));

        return assignmentRepository.findActiveRoleCodesByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<String> getRolesForUser(UUID userId) {
        return assignmentRepository.findActiveRoleCodesByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<UserWithRolesResponse> listUsersWithRoles() {
        List<User> users = userRepository.findAll();
        return users.stream()
                .map(u -> new UserWithRolesResponse(
                        u.getId(),
                        u.getEmail(),
                        u.getDisplayName(),
                        u.isEnabled(),
                        u.getCreatedAt(),
                        assignmentRepository.findActiveRoleCodesByUserId(u.getId())
                ))
                .toList();
    }

    // -------- helpers --------

    private RoleResponse toResponse(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.isSystem(),
                role.getDisplayOrder(),
                role.getCreatedAt(),
                role.getUpdatedAt()
        );
    }
}