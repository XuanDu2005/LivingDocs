package com.livingdocs.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/resend-verification}.
 *
 * <p>Always returns 202 to avoid leaking whether the email exists.
 */
public record ResendVerificationRequest(
        @NotBlank @Email @Size(max = 255) String email
) {
}
