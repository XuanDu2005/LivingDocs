package com.livingdocs.modules.notification.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code PUT /admin/notification-policies/{eventKind}} and the
 * matching workspace-scoped endpoint. The controller defaults the scope.
 */
public record NotificationPolicyRequest(
        @NotNull Boolean enabled
) {
}