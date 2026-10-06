package com.livingdocs.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/verify-email}.
 */
public record VerifyEmailRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit code")
        String code,
        VerifyEmailPurpose purpose
) {
    public enum VerifyEmailPurpose {
        REGISTER,
        EMAIL_CHANGE
    }
}
