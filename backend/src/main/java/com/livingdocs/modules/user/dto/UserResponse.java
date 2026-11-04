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
 * <p>{@code permissions} is the list of effective permission codes the
 * user holds, resolved from the {@code role_permissions} matrix. The
 * front-end uses it to hide features the user may not use (health
 * dashboard, audit log, AI settings, analytics, notification policies,
 * integrations, GitHub commit, governance) instead of offering a button
 * that would come back with 403.
 *
 * <p>{@code linkedProviders} is the list of OAuth identities
 * (Google / GitHub) currently bound to the user. This is included in
 * the response so that the front-end can render the linked-accounts
 * UI anywhere without a separate round-trip, and so that every device
 * automatically picks up newly-linked providers the next time it
 * refreshes the current user (e.g. on login, on a manual refresh, or
 * after returning from an OAuth callback).
 *
 * <p>{@code avatarUrl} is the public, directly-fetchable URL of the
 * user's profile picture, or {@code null} when they have not uploaded
 * one (the UI then falls back to initials).
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        boolean enabled,
        boolean emailVerified,
        OffsetDateTime createdAt,
        List<String> roles,
        List<String> permissions,
        List<LinkedProvidersResponse.LinkedProvider> linkedProviders
) {
    public static UserResponse from(User user) {
        return from(user, List.of(), List.of(), List.of(), null);
    }

    public static UserResponse from(User user, List<String> roles) {
        return from(user, roles, List.of(), List.of(), null);
    }

    public static UserResponse from(User user,
                                    List<String> roles,
                                    List<LinkedProvidersResponse.LinkedProvider> linkedProviders) {
        return from(user, roles, linkedProviders, List.of(), null);
    }

    /**
     * Full constructor including the resolved avatar URL.
     *
     * @param avatarUrl public URL of the avatar image, or {@code null}
     */
    public static UserResponse from(User user,
                                    List<String> roles,
                                    List<LinkedProvidersResponse.LinkedProvider> linkedProviders,
                                    String avatarUrl) {
        return from(user, roles, linkedProviders, List.of(), avatarUrl);
    }

    /**
     * Full constructor including the resolved avatar URL and the
     * effective permission codes.
     *
     * @param avatarUrl  public URL of the avatar image, or {@code null}
     * @param permissions effective permission codes of the user
     */
    public static UserResponse from(User user,
                                    List<String> roles,
                                    List<LinkedProvidersResponse.LinkedProvider> linkedProviders,
                                    List<String> permissions,
                                    String avatarUrl) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                avatarUrl,
                user.isEnabled(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                roles == null ? List.of() : List.copyOf(roles),
                permissions == null ? List.of() : List.copyOf(permissions),
                linkedProviders == null ? List.of() : List.copyOf(linkedProviders)
        );
    }
}
