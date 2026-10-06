package com.livingdocs.modules.auth.dto;

import com.livingdocs.modules.user.dto.UserResponse;

import java.time.OffsetDateTime;

/**
 * Response payload returned on successful login/registration.
 *
 * <p>Carries the JWT, its expiry, and the user record so the client can
 * immediately render the home view.
 */
public record AuthResponse(
        String token,
        OffsetDateTime expiresAt,
        UserResponse user
) {
}