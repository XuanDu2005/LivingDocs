package com.livingdocs.modules.review.dto;

import com.livingdocs.modules.review.model.ReviewDelegation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One delegation as the API returns it.
 *
 * <p>The names of both parties are resolved server-side, so the UI does
 * not have to join the member list with the delegation list itself.
 */
public record ReviewDelegationResponse(
        UUID id,
        UUID workspaceId,
        UUID fromUserId,
        String fromUserName,
        UUID toUserId,
        String toUserName,
        String tier,
        String reason,
        OffsetDateTime createdAt,
        OffsetDateTime revokedAt,
        UUID revokedBy,
        boolean active
) {
    public static ReviewDelegationResponse of(ReviewDelegation d,
                                              String fromUserName,
                                              String toUserName) {
        return new ReviewDelegationResponse(
                d.getId(),
                d.getWorkspaceId(),
                d.getFromUserId(),
                fromUserName,
                d.getToUserId(),
                toUserName,
                d.getTier(),
                d.getReason(),
                d.getCreatedAt(),
                d.getRevokedAt(),
                d.getRevokedBy(),
                d.isActive()
        );
    }
}
