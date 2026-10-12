package com.livingdocs.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code POST /auth/refresh} and {@code POST /auth/logout}.
 */
public record RefreshTokenRequest(
        @NotBlank String refreshToken
) {
}
