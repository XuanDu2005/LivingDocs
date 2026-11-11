package com.livingdocs.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/users/me/password}.
 *
 * <p>The caller authenticates with their current password, so a stolen
 * session token alone cannot take the account over. The current
 * password is required even when the account is in the "must change
 * password" state — that is exactly the case where the temporary
 * credential is known to the administrator as well as to the owner, so
 * it cannot be the thing that authorises the change.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "'currentPassword' is required")
        String currentPassword,

        @NotBlank(message = "'newPassword' is required")
        @Size(min = 12, max = 72,
                message = "'newPassword' must be between 12 and 72 characters")
        String newPassword
) {
}
