package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Request body for the "save role permissions" endpoint. The list
 * contains the IDs of the permissions that should remain granted
 * after the operation; anything not in the list will be revoked.
 */
public record UpdateRolePermissionsRequest(
        @NotNull List<UUID> grantedPermissionIds
) {
}
