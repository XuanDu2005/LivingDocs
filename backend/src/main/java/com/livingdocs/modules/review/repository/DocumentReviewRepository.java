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

    List<DocumentReview> findAllByReviewerUserIdOrderByDecidedAtDesc(UUID reviewerUserId);

    /** Latest review per (version, reviewerRole). */
    List<DocumentReview> findAllByDocumentVersionIdAndReviewerRoleOrderByDecidedAtDesc(
            UUID documentVersionId, String reviewerRole);

    long countByDocumentVersionIdAndDecision(UUID documentVersionId, ReviewDecision decision);
}