package com.livingdocs.modules.review.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.review.dto.DocumentReviewResponse;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.service.DocumentReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for the Staff / Manager review workflow.
 *
 * <p>The two flows are exposed separately so they can be wired to different
 * UI queues. A Staff reviewer hits {@code /staff/...}, a Manager hits
 * {@code /manager/...}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reviews", description = "Staff review and Manager approval workflow")
public class DocumentReviewController {

    private final DocumentReviewService reviewService;

    public DocumentReviewController(DocumentReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/staff-review")
    @Operation(summary = "Submit a Staff review verdict on a pending version")
    public DocumentReviewResponse staffReview(@PathVariable UUID workspaceId,
                                              @PathVariable UUID documentId,
                                              @PathVariable UUID versionId,
                                              @Valid @RequestBody SubmitReviewRequest req) {
        return reviewService.submitStaffReview(CurrentUser.requireId(), documentId, versionId, req);
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/manager-decision")
    @Operation(summary = "Submit a Manager approval / rejection on a version")
    public DocumentReviewResponse managerDecision(@PathVariable UUID workspaceId,
                                                  @PathVariable UUID documentId,
                                                  @PathVariable UUID versionId,
                                                  @Valid @RequestBody SubmitReviewRequest req) {
        return reviewService.submitManagerDecision(CurrentUser.requireId(), documentId, versionId, req);
    }

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/reviews")
    @Operation(summary = "List all review entries for a specific version")
    public List<DocumentReviewResponse> listReviews(@PathVariable UUID workspaceId,
                                                    @PathVariable UUID documentId,
                                                    @PathVariable UUID versionId) {
        return reviewService.reviewsForVersion(CurrentUser.requireId(), documentId, versionId);
    }

    @GetMapping("/me/reviews")
    @Operation(summary = "List the current user's review history")
    public List<DocumentReviewResponse> myReviews() {
        return reviewService.reviewsForReviewer(CurrentUser.requireId());
    }
}