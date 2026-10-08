package com.livingdocs.modules.integrations.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.livingdocs.modules.integrations.model.IntegrationConnection;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.model.IntegrationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public view of an {@link IntegrationConnection}. Sensitive fields
 * (tokens, signing secret) are reduced to booleans so the admin UI can
 * render status badges without ever seeing the cleartext.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectionDto(
        UUID id,
        IntegrationProvider provider,
        UUID workspaceId,
        String displayName,
        String externalAccount,
        String baseUrl,
        String scopes,
        boolean hasAccessToken,
        boolean hasRefreshToken,
        boolean hasWebhookSecret,
        String defaultChannel,
        IntegrationStatus status,
        OffsetDateTime connectedAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime lastSyncedAt
) {
    public static ConnectionDto of(IntegrationConnection c) {
        return new ConnectionDto(
                c.getId(),
                c.getProvider(),
                c.getWorkspaceId(),
                c.getDisplayName(),
                c.getExternalAccount(),
                c.getBaseUrl(),
                c.getScopes(),
                isPresent(c.getAccessTokenEncrypted()),
                isPresent(c.getRefreshTokenEncrypted()),
                isPresent(c.getWebhookSecretEncrypted()),
                c.getDefaultChannel(),
                c.getStatus(),
                c.getConnectedAt(),
                c.getLastUsedAt(),
                c.getLastSyncedAt()
        );
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }
}