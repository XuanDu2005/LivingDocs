package com.livingdocs.modules.admin.dto;

/** Result of a {@code POST /admin/notifications/send} call. */
public record SendNotificationResponse(
        String target,
        String roleCode,
        String title,
        String kind,
        int recipients,
        int duplicatesDropped
) {
}