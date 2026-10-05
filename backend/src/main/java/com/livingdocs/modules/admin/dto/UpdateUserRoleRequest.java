package com.livingdocs.modules.admin.dto;

import java.util.UUID;

/**
 * Body for promoting / demoting a workspace member between roles.
 *
 * <p>Promotion to {@code MANAGER} / {@code ADMIN} requires the actor to be
 * an administrator of the workspace's owning tenant.
 */
public record UpdateUserRoleRequest(UUID userId, String role) {}