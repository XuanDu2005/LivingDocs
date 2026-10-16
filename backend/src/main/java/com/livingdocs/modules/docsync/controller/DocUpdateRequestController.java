package com.livingdocs.modules.docsync.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.docsync.dto.DocUpdateRequestDto;
import com.livingdocs.modules.docsync.dto.ReviewDecisionRequest;
import com.livingdocs.modules.docsync.model.DocUpdateStatus;
import com.livingdocs.modules.docsync.service.DocUpdateRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the review-before-push workflow.
 *
 * <p>Lifecycle endpoints (all scoped by workspace + repository):
 * <ul>
 *   <li>{@code GET  /pending}              — list docs waiting for review</li>
 *   <li>{@code GET  /{requestId}}          — fetch one proposal (with diff body)</li>
 *   <li>{@code POST /{requestId}/approve}  — sign off on the proposed content</li>
 *   <li>{@code POST /{requestId}/reject}   — discard the proposal</li>
 *   <li>{@code POST /{requestId}/apply}    — (manager) push to Git as a PR</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/doc-updates")
@Tag(name = "Doc Update Requests", description = "Review-before-push workflow for documentation updates")
public class DocUpdateRequestController {

    private final DocUpdateRequestService service;

    public DocUpdateRequestController(DocUpdateRequestService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List doc update requests for a repository, optionally filtered by status")
    public List<DocUpdateRequestDto> list(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) DocUpdateStatus status) {
        UUID actorId = CurrentUser.requireId();
        return service.listForRepository(workspaceId, repositoryId, actorId, status);
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "Get a single doc update request (with full diff content)")
    public DocUpdateRequestDto get(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID requestId) {
        UUID actorId = CurrentUser.requireId();
        return service.get(workspaceId, requestId, actorId);
    }

    @PostMapping("/{requestId}/approve")
    @Operation(summary = "Approve a pending request (any member)")
    public DocUpdateRequestDto approve(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID requestId,
            @RequestBody(required = false) @Valid ReviewDecisionRequest body) {
        UUID actorId = CurrentUser.requireId();
        String comment = body == null ? null : body.comment();
        return service.approve(workspaceId, requestId, actorId, comment);
    }

    @PostMapping("/{requestId}/reject")
    @Operation(summary = "Reject a pending request (any member)")
    public DocUpdateRequestDto reject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID requestId,
            @RequestBody(required = false) @Valid ReviewDecisionRequest body) {
        UUID actorId = CurrentUser.requireId();
        String comment = body == null ? null : body.comment();
        return service.reject(workspaceId, requestId, actorId, comment);
    }

    @PostMapping("/{requestId}/apply")
    @Operation(summary = "Apply an APPROVED request as a GitHub PR (manager only)")
    public ResponseEntity<Map<String, Object>> apply(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID requestId) {
        UUID actorId = CurrentUser.requireId();
        DocUpdateRequestDto result = service.apply(workspaceId, requestId, actorId);
        return ResponseEntity.ok(Map.of(
                "request", result,
                "prUrl", result.appliedPrUrl() == null ? "" : result.appliedPrUrl()
        ));
    }
}
