package com.livingdocs.modules.auth.dto;

import com.livingdocs.modules.user.dto.UserResponse;

import java.time.OffsetDateTime;

/**
 * Response payload returned on successful login/registration.
 *
 * <p>Carries the short-lived access JWT, its expiry, a long-lived refresh
 * token (used to mint new access tokens without re-authentication), the
 * refresh token's expiry, and the user record so the client can
 * immediately render the home view.
 */
public record AuthResponse(
        String token,
        OffsetDateTime expiresAt,
        String refreshToken,
        OffsetDateTime refreshExpiresAt,
        UserResponse user
) {
}
