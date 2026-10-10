package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.PermissionResponse;
import com.livingdocs.modules.admin.dto.RolePermissionMatrixResponse;
import com.livingdocs.modules.admin.dto.UpdateRolePermissionsRequest;
import com.livingdocs.modules.admin.service.RolePermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administrator endpoints for the role-permission matrix.
 *
 * <p>The list of permissions is global; per-role grants are managed
 * through the matrix endpoint. ADMIN's row is pinned to "all granted"
 * at the service layer to keep the platform self-managing.
 */
@RestController
@RequestMapping("/api/v1/admin/permissions")
@Tag(name = "Admin · Permissions", description = "Role-permission matrix administration")
public class AdminRolePermissionController {

    private final RolePermissionService rolePermissionService;

    public AdminRolePermissionController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    @GetMapping
    @RequirePlatformRole("ADMIN")
    @Operation(summary = "List all platform permissions in the catalogue")
    public List<PermissionResponse> listPermissions() {
        return rolePermissionService.listPermissions();
    }

    @GetMapping("/roles/{roleId}/matrix")
    @RequirePlatformRole("ADMIN")
    @Operation(summary = "Get the role-permission matrix for a role")
    public RolePermissionMatrixResponse getMatrix(@PathVariable("roleId") UUID roleId) {
        return rolePermissionService.getMatrix(roleId);
    }

    @PutMapping("/roles/{roleId}/matrix")
    @RequirePlatformRole("ADMIN")
    @Operation(summary = "Replace the role-permission matrix for a role")
    public RolePermissionMatrixResponse updateMatrix(@PathVariable("roleId") UUID roleId,
                                                     @Valid @RequestBody UpdateRolePermissionsRequest request) {
        return rolePermissionService.updateMatrix(CurrentUser.requireId(), roleId, request);
    }
}
