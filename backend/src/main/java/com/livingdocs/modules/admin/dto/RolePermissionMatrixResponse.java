package com.livingdocs.modules.admin.dto;

import java.util.List;
import java.util.UUID;

/**
 * The role-permission matrix payload: a role with the full list of
 * permissions it can hold and which of those are currently granted.
 *
 * <p>Used by the admin UI to render a single role's tab. To save
 * changes the client posts the {@code grantedPermissionIds} subset
 * back; the service diffs and writes the new grants / revokes.
 */
public record RolePermissionMatrixResponse(
        UUID roleId,
        String roleCode,
        String roleName,
        boolean roleSystem,
        int totalPermissions,
        int grantedCount,
        List<RolePermissionResponse> permissions
) {
}
