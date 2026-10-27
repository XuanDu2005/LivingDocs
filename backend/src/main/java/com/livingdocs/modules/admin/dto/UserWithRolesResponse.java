package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A user together with their active platform roles.
 *
 * <p>{@code avatarUrl} is the public URL of the user's profile picture
 * (or {@code null} when they have not uploaded one) so the admin user
 * table can render the avatar without a second round-trip.
 */
public record UserWithRolesResponse(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        boolean enabled,
        OffsetDateTime createdAt,
        List<String> roles
) {}
