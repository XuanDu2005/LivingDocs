package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrative operations that span multiple modules.
 *
 * <p>Platform role enforcement happens at the controller boundary via
 * {@link com.livingdocs.common.security.RequirePlatformRole}. This
 * service is responsible for the domain operations themselves:
 * listing users and toggling the {@code enabled} flag. Role assignment
 * itself lives in {@link RoleService} so there is a single source of
 * truth.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AdminService(UserRepository userRepository,
                        AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public User getById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /**
     * Toggle a user's enabled flag. Disabling an already-disabled user or
     * enabling an already-enabled user is a no-op (returns the same User).
     * Admins cannot disable themselves — that would lock them out of the
     * platform.
     */
    @Transactional
    public User setEnabled(UUID actorId, UUID userId, boolean enabled) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (target.getId().equals(actorId)) {
            throw new ForbiddenException("You cannot change the enabled status of your own account.");
        }
        boolean previous = target.isEnabled();
        if (previous != enabled) {
            target.setEnabled(enabled);
            auditLogService.record(actorId, "ADMIN", "user.enabled.toggle",
                    "user", userId.toString(), null,
                    Map.of("enabled", enabled, "previous", previous));
        }
        return target;
    }

    /**
     * Kept for backward compatibility with the
     * {@code PUT /admin/users/{userId}/role} endpoint. New role management
     * goes through {@link RoleService#replaceRoles}; this method now just
     * records a deprecated audit entry and refuses to act.
     */
    @Transactional
    public User updateRole(UUID actorId, UUID userId, String role) {
        throw new ConflictException("Legacy role update is disabled. Use PUT /api/v1/admin/users/{userId}/roles instead.");
    }
}