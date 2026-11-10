package com.livingdocs.modules.review.dto;

import java.util.List;
import java.util.UUID;

/**
 * The caller's personal review inbox for one workspace.
 *
 * <p>Contains the viewer's platform roles and capabilities alongside the
 * three queue buckets, so a client can render exactly the sections the
 * viewer is allowed to act on without making extra round-trips.
 *
 * <p>The fourth bucket, {@code mySubmissions}, is not a review queue in
 * the "you have to act on this" sense — it is a read-only list of the
 * versions the caller submitted for review, with the verdict they
 * received. It exists for the author side of the workflow: a developer
 * who has no reviewing role still needs to see whether their draft was
 * approved, rejected, or sent back for changes.
 */
public record ReviewQueueResponse(
        UUID workspaceId,
        List<String> platformRoles,
        boolean member,
        boolean workspaceManager,
        boolean documentationOwner,
        boolean canStaffReview,
        boolean canManagerApprove,
        List<ReviewQueueItemResponse> staffQueue,
        List<ReviewQueueItemResponse> managerQueue,
        List<ReviewQueueItemResponse> publishQueue,
        List<SubmittedVersionResponse> mySubmissions
) {
}
