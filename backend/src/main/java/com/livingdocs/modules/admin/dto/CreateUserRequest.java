package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/admin/users}.
 *
 * <p>An administrator creates the account with a temporary password and
 * the platform roles the person should hold. The account starts with
 * {@code mustChangePassword = true}: the temporary credential is only
 * meant to get the person to the login screen, and it has to be
 * replaced before the account can be used for anything else.
 *
 * <p>{@code emailVerified} defaults to {@code true} because the
 * administrator vouches for the address — otherwise the new account
 * would sit in the verification queue waiting for an email that the
 * person may never receive (the address was supplied by an
 * administrator, not by the person themselves). It can be set to
 * {@code false} to reproduce the self-registration flow.
 */
public record CreateUserRequest(
        @NotBlank(message = "'email' is required")
        @Email(message = "'email' is not a valid address")
        @Size(max = 255, message = "'email' must not exceed 255 characters")
        String email,

        @NotBlank(message = "'displayName' is required")
        @Size(max = 120, message = "'displayName' must not exceed 120 characters")
        String displayName,

        @NotBlank(message = "'temporaryPassword' is required")
        @Size(min = 12, max = 72,
                message = "'temporaryPassword' must be between 12 and 72 characters")
        String temporaryPassword,

        @NotEmpty(message = "'roles' must contain at least one platform role")
        List<String> roles,

        Boolean emailVerified,

        Boolean enabled
) {
}
