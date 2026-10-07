package com.livingdocs.modules.auth.dto;

import java.util.List;

/**
 * Response payload for {@code GET /api/v1/auth/oauth/providers}.
 */
public record LinkedProvidersResponse(
        List<LinkedProvider> providers
) {
    public record LinkedProvider(
            String provider,
            String providerEmail,
            String displayName,
            String avatarUrl
    ) {
    }
}
