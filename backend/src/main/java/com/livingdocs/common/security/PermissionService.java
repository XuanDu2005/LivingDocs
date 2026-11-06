package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.modules.admin.repository.RolePermissionRepository;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the <em>effective permission codes</em> a user holds.
 *
 * <p>The platform role axis answers "which tier is this person?", but
 * several capabilities are not tier-shaped: reading the AI provider
 * configuration, viewing the audit trail, seeing operational health
 * metrics, publishing to GitHub. Those live in the
 * {@code role_permissions} matrix (seeded by Flyway V16 and extended by
 * V36), which maps a role onto a set of permission codes.
 *
 * <p>This service resolves the union of the permissions granted to every
 * active role the user holds. A user with no roles holds no
 * permissions, so every guarded endpoint refuses them — the
 * "no privileges" default is what makes the matrix meaningful.
 *
 * <p>Results are cached under the {@code user-permissions} cache, the
 * same cache the admin matrix uses. Editing the matrix evicts the whole
 * cache, so a permission change takes effect on the next request.
 */
@Service
public class PermissionService {

    /**
     * The name of the cache that holds the resolved permission sets.
     *
     * <p>It is a constant because a migration that changes the matrix
     * has to be able to evict the whole cache — a stale entry would
     * keep denying a capability that was just granted (see
     * {@code PermissionCacheEvictionRunner}).
     */
    public static final String PERMISSIONS_CACHE = "user-permissions";

    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final RolePermissionRepository rolePermissionRepository;

    /**
     * Self-reference used to go through the Spring proxy when the
     * cached {@link #effectivePermissions} is called from another
     * method of this class. A direct {@code this.effectivePermissions()}
     * call would bypass the {@code @Cacheable} advice and hit the
     * database on every request.
     */
    private final ObjectProvider<PermissionService> selfProvider;

    public PermissionService(UserRoleAssignmentRepository roleAssignmentRepository,
                              RolePermissionRepository rolePermissionRepository,
                              ObjectProvider<PermissionService> selfProvider) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.selfProvider = selfProvider;
    }

    /**
     * The permission codes the user holds through their active platform
     * roles. Empty when the user holds no role, or when no role grants
     * a permission.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = PermissionService.PERMISSIONS_CACHE, key = "'effective:' + #userId")
    public Set<String> effectivePermissions(UUID userId) {
        if (userId == null) {
            // A mutable HashSet (not Set.of()) so the value survives the
            // Jackson round-trip through the Redis cache.
            return new HashSet<>();
        }
        Set<UUID> roleIds = new HashSet<>(
                roleAssignmentRepository.findActiveByUserId(userId).stream()
                        .map(a -> a.getRoleId())
                        .toList());
        if (roleIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> codes = new HashSet<>();
        for (UUID roleId : roleIds) {
            codes.addAll(rolePermissionRepository.findPermissionCodesByRoleId(roleId));
        }
        return codes;
    }

    /**
     * Does the user hold at least one of the given permission codes?
     */
    @Transactional(readOnly = true)
    public boolean hasAnyPermission(UUID userId, String... permissionCodes) {
        if (permissionCodes == null || permissionCodes.length == 0) {
            return false;
        }
        Set<String> held = selfProvider.getObject().effectivePermissions(userId);
        for (String code : permissionCodes) {
            if (held.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Assert the user holds at least one of the given permission codes.
     *
     * @throws ForbiddenException when the user holds none of them
     */
    @Transactional(readOnly = true)
    public void requireAnyPermission(UUID userId, String... permissionCodes) {
        if (hasAnyPermission(userId, permissionCodes)) {
            return;
        }
        throw new ForbiddenException(
                "This action requires one of these permissions: "
                        + String.join(", ", permissionCodes));
    }

    /**
     * The active platform role codes the user holds. Used by the
     * interceptor to short-circuit ADMIN (which always passes).
     */
    @Transactional(readOnly = true)
    public List<String> platformRoles(UUID userId) {
        return roleAssignmentRepository.findActiveRoleCodesByUserId(userId);
    }
}
