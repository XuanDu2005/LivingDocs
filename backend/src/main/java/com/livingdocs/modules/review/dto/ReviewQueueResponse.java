package com.livingdocs.modules.review.dto;

import java.util.List;
import java.util.UUID;

/**
 * The caller's personal review inbox for one workspace.
 *
 * <p>Contains the viewer's platform roles and capabilities alongside the
 * three queue buckets, so a client can render exactly the sections the
 * viewer is allowed to act on without making extra round-trips.
 */
public record ReviewQueueResponse(
        UUID workspaceId,
        List<String> platformRoles,
        boolean member,
        boolean workspaceManager,
        boolean canStaffReview,
        boolean canManagerApprove,
        List<ReviewQueueItemResponse> staffQueue,
        List<ReviewQueueItemResponse> managerQueue,
        List<ReviewQueueItemResponse> publishQueue
) {
}
