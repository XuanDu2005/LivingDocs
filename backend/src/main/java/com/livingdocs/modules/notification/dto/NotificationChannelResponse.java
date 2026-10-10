package com.livingdocs.modules.notification.dto;

import com.livingdocs.modules.notification.model.NotificationChannel;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationChannelResponse(
    UUID id,
    UUID workspaceId,
    String eventKind,
    boolean inAppEnabled,
    boolean emailEnabled,
    String emailRecipients,
    OffsetDateTime updatedAt
) {
    public static NotificationChannelResponse from(NotificationChannel c) {
        return new NotificationChannelResponse(
            c.getId(),
            c.getWorkspaceId(),
            c.getEventKind(),
            c.isInAppEnabled(),
            c.isEmailEnabled(),
            c.getEmailRecipients(),
            c.getUpdatedAt()
        );
    }
}
