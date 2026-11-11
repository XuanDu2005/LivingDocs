package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/users/{userId}/reset-password}.
 *
 * <p>The administrator supplies the replacement password directly
 * instead of triggering the self-service "forgot password" email
 * flow. Two reasons: the person may have lost access to the mailbox
 * entirely, and an administrator resetting somebody else's password is
 * an audited action that should not depend on a delivery channel.
 *
 * <p>Like every administrator-issued password, the new one puts the
 * account into the "must change password" state — the administrator
 * chose the credential, so the account owner still has to replace it
 * with one only they know.
 */
public record ResetUserPasswordRequest(
        @NotBlank(message = "'newPassword' is required")
        @Size(min = 12, max = 72,
                message = "'newPassword' must be between 12 and 72 characters")
        String newPassword
) {
}
