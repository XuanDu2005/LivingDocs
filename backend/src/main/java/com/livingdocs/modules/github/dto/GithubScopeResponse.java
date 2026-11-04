package com.livingdocs.modules.github.dto;

import java.time.OffsetDateTime;

/**
 * One repository access scope granted to the GitHub application.
 */
public record GithubScopeResponse(
        String id,
        String scope,
        String description,
        boolean active,
        String grantedAt,
        String revokedAt
) {
    public static GithubScopeResponse from(
            com.livingdocs.modules.github.model.GithubScopeGrant g) {
        return new GithubScopeResponse(
                g.getId().toString(),
                g.getScope(),
                g.getDescription(),
                g.isActive(),
                g.getGrantedAt() == null ? null : g.getGrantedAt().toString(),
                g.getRevokedAt() == null ? null : g.getRevokedAt().toString());
    }
}
