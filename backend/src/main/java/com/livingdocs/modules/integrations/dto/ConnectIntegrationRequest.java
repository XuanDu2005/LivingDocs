package com.livingdocs.modules.integrations.dto;

import com.livingdocs.modules.integrations.model.IntegrationProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload sent from the admin UI to register or update a connection.
 * Plaintext tokens are never echoed back in responses; only the boolean
 * {@code hasAccessToken} / {@code hasWebhookSecret} flags indicate
 * whether one is stored.
 */
public record ConnectIntegrationRequest(
        @NotNull IntegrationProvider provider,
        UUID workspaceId,
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Size(max = 255) String externalAccount,
        @Size(max = 500) String baseUrl,
        String scopes,
        String accessToken,
        String refreshToken,
        String webhookSecret,
        @Size(max = 120) String defaultChannel
) {
}