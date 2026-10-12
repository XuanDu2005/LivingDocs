package com.livingdocs.modules.github.dto;

import com.livingdocs.modules.github.model.Commit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire-format projection of {@link Commit}.
 */
public record CommitDto(
        UUID id,
        UUID repositoryId,
        String sha,
        String shortSha,
        String message,
        String authorName,
        String authorEmail,
        OffsetDateTime authoredAt,
        OffsetDateTime committedAt,
        String url,
        String branch
) {
    public static CommitDto from(Commit c) {
        String s = c.getSha() == null ? null : c.getSha();
        return new CommitDto(
                c.getId(),
                c.getRepositoryId(),
                s,
                s == null ? null : s.substring(0, Math.min(7, s.length())),
                c.getMessage(),
                c.getAuthorName(),
                c.getAuthorEmail(),
                c.getAuthoredAt(),
                c.getCommittedAt(),
                c.getUrl(),
                c.getBranch()
        );
    }
}
