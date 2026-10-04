package com.livingdocs.modules.github.dto;

import java.time.OffsetDateTime;

/**
 * Public representation of a connected GitHub account.
 *
 * <p>Intentionally omits the access token; that field is server-side
 * only.
 */
public record GithubConnectionResponse(
        String id,
        String githubLogin,
        Long githubUserId,
        String scope,
        String status,
        OffsetDateTime connectedAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime lastSyncedAt) {
}