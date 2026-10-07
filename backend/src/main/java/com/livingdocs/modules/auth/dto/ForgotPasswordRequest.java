package com.livingdocs.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/forgot-password}.
 */
public record ForgotPasswordRequest(
        @NotBlank @Email @Size(max = 255) String email
) {
}
