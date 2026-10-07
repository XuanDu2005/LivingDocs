package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/v1/admin/users/{userId}/enabled}.
 *
 * <p>Wrapped in its own DTO so Jackson can deserialise the payload
 * deterministically (an untyped {@code Map<String, Boolean>} cannot be
 * deserialised from {@code "{\"enabled\":false}"} without additional
 * Jackson configuration).
 */
public record SetUserEnabledRequest(
        @NotNull(message = "'enabled' is required")
        Boolean enabled
) {}