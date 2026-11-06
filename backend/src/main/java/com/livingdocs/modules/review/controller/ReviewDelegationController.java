package com.livingdocs.modules.review.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.review.dto.CreateDelegationRequest;
import com.livingdocs.modules.review.dto.ReviewDelegationResponse;
import com.livingdocs.modules.review.service.ReviewDelegationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Review delegation endpoints.
 *
 * <p>Requirement #12 of the MANAGER role: hand the review or approval
 * work of the workspace to somebody else.
 *
 * <h2>Who may call these</h2>
 * The platform role {@code MANAGER} (or {@code ADMIN}) <em>and</em> the
 * {@code REVIEW_DELEGATION_MANAGE} permission <em>and</em> workspace
 * membership. The delegate must be a workspace member who holds the
 * permission of the tier being delegated.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reviews", description = "Staff review and Manager approval workflow")
public class ReviewDelegationController {

    private final ReviewDelegationService delegationService;

    public ReviewDelegationController(ReviewDelegationService delegationService) {
        this.delegationService = delegationService;
    }

    @PostMapping("/workspaces/{workspaceId}/reviews/delegations")
    @Operation(summary = "Delegate the review or approval work of a workspace to a member")
    public ResponseEntity<ReviewDelegationResponse> create(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateDelegationRequest req) {
        ReviewDelegationResponse body = delegationService.create(
                CurrentUser.requireId(), workspaceId, req);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/workspaces/{workspaceId}/reviews/delegations")
    @Operation(summary = "List the review delegations of a workspace")
    public ResponseEntity<List<ReviewDelegationResponse>> list(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(delegationService.list(CurrentUser.requireId(), workspaceId));
    }

    @DeleteMapping("/workspaces/{workspaceId}/reviews/delegations/{delegationId}")
    @Operation(summary = "End an active review delegation")
    public ResponseEntity<ReviewDelegationResponse> revoke(
            @PathVariable UUID workspaceId,
            @PathVariable UUID delegationId) {
        return ResponseEntity.ok(delegationService.revoke(
                CurrentUser.requireId(), workspaceId, delegationId));
    }
}
