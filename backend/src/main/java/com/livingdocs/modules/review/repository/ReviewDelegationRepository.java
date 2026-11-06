package com.livingdocs.modules.review.repository;

import com.livingdocs.modules.review.model.ReviewDelegation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewDelegationRepository extends JpaRepository<ReviewDelegation, UUID> {

    /**
     * Every delegation of the workspace, in force or revoked.
     *
     * <p>The list view shows the whole handover history — a reader wants
     * to know that somebody delegated three weeks ago and the delegation
     * was ended since.
     */
    List<ReviewDelegation> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    /**
     * The delegations that are still in force in the workspace.
     */
    List<ReviewDelegation> findAllByWorkspaceIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID workspaceId);

    /**
     * The delegations that are still in force for one tier.
     *
     * <p>This is the lookup the notification path uses: the recipients
     * of a tier are the people who hold the permission <em>plus</em> the
     * delegates of that tier.
     */
    List<ReviewDelegation> findAllByWorkspaceIdAndTierAndRevokedAtIsNullOrderByCreatedAtDesc(
            UUID workspaceId, String tier);

    Optional<ReviewDelegation> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /**
     * The active delegation of one delegate in one tier, if there is one.
     *
     * <p>Used to refuse a duplicate delegation with a clear message
     * instead of letting the partial unique index raise a constraint
     * violation.
     */
    Optional<ReviewDelegation> findFirstByWorkspaceIdAndToUserIdAndTierAndRevokedAtIsNull(
            UUID workspaceId, UUID toUserId, String tier);
}
