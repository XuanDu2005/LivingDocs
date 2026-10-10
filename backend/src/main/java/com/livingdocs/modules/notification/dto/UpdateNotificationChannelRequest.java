package com.livingdocs.modules.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNotificationChannelRequest(
    @NotBlank
    @Size(max = 64)
    String eventKind,

    boolean inAppEnabled,

    boolean emailEnabled,

    @Size(max = 500)
    String emailRecipients
) {}
