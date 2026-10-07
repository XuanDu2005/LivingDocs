package com.livingdocs.modules.user.dto;

import com.livingdocs.modules.user.model.User;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public representation of a user. Never exposes the password hash.
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        boolean enabled,
        boolean emailVerified,
        OffsetDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.isEnabled(),
                user.isEmailVerified(),
                user.getCreatedAt()
        );
    }
}
