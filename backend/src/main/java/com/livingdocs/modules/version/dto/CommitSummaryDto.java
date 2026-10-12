package com.livingdocs.modules.version.dto;

import com.livingdocs.modules.github.model.Commit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Minimal projection of a {@link Commit} that is embedded in a
 * {@link DocumentVersionResponse} so the UI can render "Triggered by commit
 * abc1234" badges without an extra round-trip.
 */
public record CommitSummaryDto(
        UUID id,
        String sha,
        String shortSha,
        String message,
        String authorName,
        OffsetDateTime authoredAt,
        String url) {
    public static CommitSummaryDto from(Commit c) {
        if (c == null) return null;
        String s = c.getSha();
        return new CommitSummaryDto(
                c.getId(),
                s,
                s == null ? null : s.substring(0, Math.min(7, s.length())),
                c.getMessage(),
                c.getAuthorName(),
                c.getAuthoredAt(),
                c.getUrl()
        );
    }
}
