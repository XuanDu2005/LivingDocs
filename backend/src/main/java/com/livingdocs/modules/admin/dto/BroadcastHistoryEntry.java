package com.livingdocs.modules.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One "campaign" row — a single admin send, possibly to many recipients.
 *
 * <p>Built from the audit log so a broadcast to 200 users shows up as
 * 1 row here, not 200 duplicate rows. The {@code payload} field keeps
 * the {@code title}, {@code kind}, {@code target} and {@code recipients}
 * that were stored when the send was recorded.
 */
public record BroadcastHistoryEntry(
        UUID id,
        UUID actorUserId,
        String actorRole,
        OffsetDateTime sentAt,
        String action,
        String target,
        String roleCode,
        String kind,
        String title,
        int recipients,
        String body,
        String link
) {
}