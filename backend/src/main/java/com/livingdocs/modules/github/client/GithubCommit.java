package com.livingdocs.modules.github.client;

import java.time.OffsetDateTime;

/**
 * Wire-format projection of a GitHub commit, used by the commit
 * ingestion pipeline and surfaced in the Repository page UI.
 */
public record GithubCommit(
        String sha,
        String message,
        String authorName,
        String authorEmail,
        OffsetDateTime authoredAt,
        OffsetDateTime committedAt,
        String url
) {
}
