package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.PermissionResponse;
import com.livingdocs.modules.admin.dto.RolePermissionMatrixResponse;
import com.livingdocs.modules.admin.dto.RolePermissionResponse;
import com.livingdocs.modules.admin.dto.UpdateRolePermissionsRequest;
import com.livingdocs.modules.admin.model.Permission;
import com.livingdocs.modules.admin.model.Role;
import com.livingdocs.modules.admin.model.RolePermission;
import com.livingdocs.modules.admin.repository.PermissionRepository;
import com.livingdocs.modules.admin.repository.RolePermissionRepository;
import com.livingdocs.modules.admin.repository.RoleRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read and update operations for the role-permission matrix.
 *
 * <p>The matrix is intentionally coarse: the admin sees a grid of
 * roles × permissions, toggles checkboxes, and the service applies the
 * diff with one audit entry. ADMIN's row is read-only — the catalogue
 * cannot be used to revoke a platform administrator's privileges,
 * because that would brick the only role allowed to manage the matrix
 * itself.
 */
@Service
public class RolePermissionService {

    private static final String SYSTEM_ADMIN_CODE = "ADMIN";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AuditLogService auditLogService;

    public RolePermissionService(RoleRepository roleRepository,
                                 PermissionRepository permissionRepository,
                                 RolePermissionRepository rolePermissionRepository,
                                 AuditLogService auditLogService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.auditLogService = auditLogService;
    }

    // -------- catalogue --------

    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return permissionRepository.findAllByOrderByCategoryAscDisplayOrderAscCodeAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Build the matrix view for a single role: every permission in the
     * catalogue with a {@code granted} flag based on whether the
     * role_permissions table currently has a row.
     */
    @Transactional(readOnly = true)
    public RolePermissionMatrixResponse getMatrix(UUID roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role not found"));
        List<Permission> all = permissionRepository.findAllByOrderByCategoryAscDisplayOrderAscCodeAsc();
        Set<UUID> granted = new HashSet<>();
        Map<UUID, java.time.OffsetDateTime> grantedAt = new HashMap<>();
        for (RolePermission rp : rolePermissionRepository.findByRoleId(roleId)) {
            granted.add(rp.getPermissionId());
            grantedAt.put(rp.getPermissionId(), rp.getGrantedAt());
        }
        List<RolePermissionResponse> rows = new ArrayList<>();
        for (Permission p : all) {
            boolean isGranted = granted.contains(p.getId());
            rows.add(new RolePermissionResponse(
                    p.getId(),
                    p.getCode(),
                    p.getName(),
                    p.getDescription(),
                    p.getCategory(),
                    p.getDisplayOrder(),
                    isGranted,
                    isGranted ? grantedAt.get(p.getId()) : null
            ));
        }
        return new RolePermissionMatrixResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.isSystem(),
                all.size(),
                granted.size(),
                rows
        );
    }

    /**
     * Replace the granted permission set for a role. Granted list is
     * normalized against the current state, the diff is persisted, and
     * an audit entry summarises the change. ADMIN's grants are pinned:
     * any revocation attempts are ignored.
     */
    @Transactional
    public RolePermissionMatrixResponse updateMatrix(UUID actorId,
                                                     UUID roleId,
                                                     UpdateRolePermissionsRequest request) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role not found"));

        Set<UUID> requested = new HashSet<>(request.grantedPermissionIds());

        // Build a lookup of valid permission IDs from the catalogue.
        Set<UUID> validIds = new HashSet<>();
        for (Permission p : permissionRepository.findAll()) {
            validIds.add(p.getId());
        }
        // Filter out unknown IDs to avoid silent no-ops.
        requested.retainAll(validIds);

        // For ADMIN, ignore removals (so the platform can never be locked out).
        if (SYSTEM_ADMIN_CODE.equals(role.getCode())) {
            for (Permission p : permissionRepository.findAll()) {
                requested.add(p.getId());
            }
        }

        Set<UUID> current = new HashSet<>();
        for (RolePermission rp : rolePermissionRepository.findByRoleId(roleId)) {
            current.add(rp.getPermissionId());
        }

        Set<UUID> toGrant = new HashSet<>(requested);
        toGrant.removeAll(current);
        Set<UUID> toRevoke = new HashSet<>(current);
        toRevoke.removeAll(requested);

        for (UUID pid : toGrant) {
            rolePermissionRepository.save(new RolePermission(roleId, pid, actorId));
        }
        for (UUID pid : toRevoke) {
            rolePermissionRepository.deleteByRoleIdAndPermissionId(roleId, pid);
        }

        if (!toGrant.isEmpty() || !toRevoke.isEmpty()) {
            // Look up permission codes for the audit entry.
            Map<UUID, String> codeById = new HashMap<>();
            for (Permission p : permissionRepository.findAll()) {
                codeById.put(p.getId(), p.getCode());
            }
            List<String> grantedCodes = new ArrayList<>();
            for (UUID id : toGrant) grantedCodes.add(codeById.getOrDefault(id, id.toString()));
            List<String> revokedCodes = new ArrayList<>();
            for (UUID id : toRevoke) revokedCodes.add(codeById.getOrDefault(id, id.toString()));
            java.util.Map<String, Object> meta = new HashMap<>();
            meta.put("role", role.getCode());
            meta.put("granted", grantedCodes);
            meta.put("revoked", revokedCodes);
            meta.put("grantedCount", requested.size());
            auditLogService.record(actorId, "ADMIN", "role.permissions.update",
                    "role", roleId.toString(), null, meta);
        }

        return getMatrix(roleId);
    }

    // -------- helpers --------

    private PermissionResponse toResponse(Permission p) {
        return new PermissionResponse(
                p.getId(),
                p.getCode(),
                p.getName(),
                p.getDescription(),
                p.getCategory(),
                p.getDisplayOrder(),
                p.isSystem(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
