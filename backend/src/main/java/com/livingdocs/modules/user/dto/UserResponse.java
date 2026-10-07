package com.livingdocs.modules.user.dto;

import com.livingdocs.modules.user.model.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Public representation of a user. Never exposes the password hash.
 *
 * <p>{@code roles} is the list of active platform role codes the user
 * currently holds (e.g. ADMIN, MANAGER, STAFF, TECHNICAL_LEAD,
 * DEVELOPER). It is intentionally separate from any workspace-level role
 * the user may have.
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        boolean enabled,
        boolean emailVerified,
        OffsetDateTime createdAt,
        List<String> roles
) {
    public static UserResponse from(User user) {
        return from(user, List.of());
    }

    public static UserResponse from(User user, List<String> roles) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.isEnabled(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                roles == null ? List.of() : List.copyOf(roles)
        );
    }
}