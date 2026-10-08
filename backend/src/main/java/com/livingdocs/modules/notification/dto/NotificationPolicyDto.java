package com.livingdocs.modules.notification.dto;

/**
 * Projected view of {@link com.livingdocs.modules.notification.model.NotificationPolicy}.
 *
 * <p>Always includes the canonical {@code enabled} flag (true if no row
 * exists yet for this scope) so the UI can render a toggle without doing
 * a separate "is the row present?" lookup.
 */
public record NotificationPolicyDto(
        String eventKind,
        Boolean enabled,
        String scope
) {
    public static NotificationPolicyDto of(String eventKind, boolean enabled, String scope) {
        return new NotificationPolicyDto(eventKind, enabled, scope);
    }
}