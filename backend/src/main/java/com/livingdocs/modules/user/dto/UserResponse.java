package com.livingdocs.modules.user.dto;

import com.livingdocs.modules.auth.dto.LinkedProvidersResponse;
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
 *
 * <p>{@code linkedProviders} is the list of OAuth identities
 * (Google / GitHub) currently bound to the user. This is included in
 * the response so that the front-end can render the linked-accounts
 * UI anywhere without a separate round-trip, and so that every device
 * automatically picks up newly-linked providers the next time it
 * refreshes the current user (e.g. on login, on a manual refresh, or
 * after returning from an OAuth callback).
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        boolean enabled,
        boolean emailVerified,
        OffsetDateTime createdAt,
        List<String> roles,
        List<LinkedProvidersResponse.LinkedProvider> linkedProviders
) {
    public static UserResponse from(User user) {
        return from(user, List.of(), List.of());
    }

    public static UserResponse from(User user, List<String> roles) {
        return from(user, roles, List.of());
    }

    public static UserResponse from(User user,
                                    List<String> roles,
                                    List<LinkedProvidersResponse.LinkedProvider> linkedProviders) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.isEnabled(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                roles == null ? List.of() : List.copyOf(roles),
                linkedProviders == null ? List.of() : List.copyOf(linkedProviders)
        );
    }
}
