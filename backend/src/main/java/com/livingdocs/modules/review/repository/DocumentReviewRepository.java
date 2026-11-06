package com.livingdocs.modules.review.repository;

import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentReviewRepository extends JpaRepository<DocumentReview, UUID> {

    List<DocumentReview> findAllByDocumentVersionIdOrderByDecidedAtDesc(UUID documentVersionId);

    /**
     * Every review of the given versions, newest first.
     *
     * <p>Used to build the "my submissions" list without running one
     * query per version.
     */
    List<DocumentReview> findAllByDocumentVersionIdInOrderByDecidedAtDesc(
            java.util.Collection<UUID> documentVersionIds);

    List<DocumentReview> findAllByReviewerUserIdOrderByDecidedAtDesc(UUID reviewerUserId);

    /** Latest review per (version, reviewerRole). */
    List<DocumentReview> findAllByDocumentVersionIdAndReviewerRoleOrderByDecidedAtDesc(
            UUID documentVersionId, String reviewerRole);

    /**
     * The verdicts of one tier that are still current.
     *
     * <p>"Current" means not superseded: a verdict that a later verdict
     * in the same tier replaced is kept for the trail but no longer
     * counts as the authoritative opinion. The workflow reasons about
     * the current rows only — that is what makes "the latest verdict
     * wins" mean what it says even after a reviewer corrected
     * themselves.
     */
    List<DocumentReview> findAllByDocumentVersionIdAndReviewerRoleAndSupersededAtIsNullOrderByDecidedAtDesc(
            UUID documentVersionId, String reviewerRole);

    /**
     * Every review of the given versions that is still current.
     */
    List<DocumentReview> findAllByDocumentVersionIdInAndSupersededAtIsNullOrderByDecidedAtDesc(
            java.util.Collection<UUID> documentVersionIds);

    long countByDocumentVersionIdAndDecision(UUID documentVersionId, ReviewDecision decision);
}
