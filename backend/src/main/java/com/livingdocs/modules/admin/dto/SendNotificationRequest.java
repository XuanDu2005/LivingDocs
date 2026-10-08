package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/admin/notifications/send}.
 *
 * <p>The {@code target} describes which users get it:
 * <ul>
 *   <li>{@code target = "user"}  → {@code userId} must be set</li>
 *   <li>{@code target = "role"}  → {@code roleCode} must be set</li>
 *   <li>{@code target = "all"}   → every enabled user receives it</li>
 * </ul>
 */
public record SendNotificationRequest(
        @NotBlank String target,
        UUID userId,
        String roleCode,
        @NotBlank @Size(max = 60) String kind,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 4000) String body,
        @Size(max = 500) String link
) {
}