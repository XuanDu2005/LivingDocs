package com.livingdocs.modules.review.controller;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.review.dto.DocumentReviewResponse;
import com.livingdocs.modules.review.dto.ReviseVersionRequest;
import com.livingdocs.modules.review.dto.ReviewQueueFilter;
import com.livingdocs.modules.review.dto.ReviewQueueItemResponse;
import com.livingdocs.modules.review.dto.ReviewQueueResponse;
import com.livingdocs.modules.review.dto.SubmittedVersionResponse;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.dto.TemplateComplianceResponse;
import com.livingdocs.modules.review.service.DocumentReviewService;
import com.livingdocs.modules.review.service.TemplateComplianceService;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the Staff / Manager review workflow.
 *
 * <p>The two flows are exposed separately so they can be wired to different
 * UI queues. A Staff reviewer hits {@code /staff/...}, a Manager hits
 * {@code /manager/...}.
 *
 * <h2>Role model</h2>
 * Both the review and the approval tier accept more than one platform role:
 * {@code STAFF}/{@code TECHNICAL_LEAD}/{@code MANAGER}/{@code ADMIN} for
 * the first tier and {@code TECHNICAL_LEAD}/{@code MANAGER}/{@code ADMIN}
 * for the second. Callers must additionally be members of the workspace.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reviews", description = "Staff review and Manager approval workflow")
public class DocumentReviewController {

    private final DocumentReviewService reviewService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final TemplateComplianceService complianceService;

    public DocumentReviewController(DocumentReviewService reviewService,
                                    AuthorizationService authorizationService,
                                    DocumentRepository documentRepository,
                                    TemplateComplianceService complianceService) {
        this.reviewService = reviewService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.complianceService = complianceService;
    }

    /**
     * Make sure the document really belongs to the workspace named in the
     * path.
     *
     * <p>Every endpoint below carries a {@code workspaceId} segment, but
     * the service layer only ever looked at {@code document.workspaceId}.
     * A caller could therefore point a workspace they are a member of at
     * a document in a workspace they are not — the membership check then
     * passed against the <em>document's</em> workspace, and the
     * {@code workspaceId} in the URL was silently ignored. Comparing the
     * two closes that hole and makes the URL meaningful.
     */
    private void requireSameWorkspace(UUID workspaceId, UUID documentId) {
        if (workspaceId == null) {
            return;
        }
        var document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        if (!document.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Document not found in this workspace");
        }
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/submit-for-review")
    @Operation(summary = "Push a draft version into the Staff review queue")
    public Map<String, Object> submitForReview(@PathVariable UUID workspaceId,
                                               @PathVariable UUID documentId,
                                               @PathVariable UUID versionId) {
        requireSameWorkspace(workspaceId, documentId);
        var version = reviewService.submitForReview(
                CurrentUser.requireId(), documentId, versionId);
        return Map.of(
                "documentId", documentId.toString(),
                "versionId", version.getId().toString(),
                "versionNumber", version.getVersionNumber(),
                "status", version.getStatus().name(),
                "message", "Version submitted for review.");
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/staff-review")
    @Operation(summary = "Submit a Staff review verdict on a pending version")
    public DocumentReviewResponse staffReview(@PathVariable UUID workspaceId,
                                              @PathVariable UUID documentId,
                                              @PathVariable UUID versionId,
                                              @Valid @RequestBody SubmitReviewRequest req) {
        requireSameWorkspace(workspaceId, documentId);
        return reviewService.submitStaffReview(CurrentUser.requireId(), documentId, versionId, req);
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/manager-decision")
    @Operation(summary = "Submit a Manager approval / rejection on a version")
    public DocumentReviewResponse managerDecision(@PathVariable UUID workspaceId,
                                                  @PathVariable UUID documentId,
                                                  @PathVariable UUID versionId,
                                                  @Valid @RequestBody SubmitReviewRequest req) {
        requireSameWorkspace(workspaceId, documentId);
        return reviewService.submitManagerDecision(CurrentUser.requireId(), documentId, versionId, req);
    }

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/reviews")
    @Operation(summary = "List all review entries for a specific version")
    public List<DocumentReviewResponse> listReviews(@PathVariable UUID workspaceId,
                                                    @PathVariable UUID documentId,
                                                    @PathVariable UUID versionId) {
        requireSameWorkspace(workspaceId, documentId);
        return reviewService.reviewsForVersion(CurrentUser.requireId(), documentId, versionId);
    }

    /**
     * Check a version against the template it was generated from.
     *
     * <p>Requirement #5 of the STAFF role: "verify template structure".
     * The response tells the reviewer which template sections are
     * present, which are missing, which are out of order and which are
     * empty.
     */
    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/template-compliance")
    @Operation(summary = "Check a document version against its template structure")
    public TemplateComplianceResponse templateCompliance(@PathVariable UUID workspaceId,
                                                          @PathVariable UUID documentId,
                                                          @PathVariable UUID versionId) {
        requireSameWorkspace(workspaceId, documentId);
        return complianceService.check(CurrentUser.requireId(), documentId, versionId);
    }

    @GetMapping("/me/reviews")
    @Operation(summary = "List the current user's review history")
    public List<DocumentReviewResponse> myReviews() {
        return reviewService.reviewsForReviewer(CurrentUser.requireId());
    }

    /**
     * Revise the version that is currently awaiting review.
     *
     * <p>Requirement #7 of the STAFF role: "fix small errors while
     * reviewing". The revision creates a new version that supersedes the
     * one under review; the new version keeps the original submitter and
     * stays in the queue.
     */
    @PutMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/revise")
    @Operation(summary = "Fix small errors in a version that is awaiting review")
    public DocumentVersionResponse revise(@PathVariable UUID workspaceId,
                                          @PathVariable UUID documentId,
                                          @PathVariable UUID versionId,
                                          @Valid @RequestBody ReviseVersionRequest req) {
        requireSameWorkspace(workspaceId, documentId);
        var v = reviewService.revise(CurrentUser.requireId(), documentId, versionId, req);
        return DocumentVersionResponse.from(v, null);
    }

    // -----------------------------------------------------------------
    // queues
    // -----------------------------------------------------------------

    @GetMapping("/workspaces/{workspaceId}/reviews/pending-on-me")
    @Operation(summary = "Everything the caller may act on in this workspace, split by review tier")
    public ReviewQueueResponse pendingOnMe(@PathVariable UUID workspaceId,
                                           @RequestParam(required = false) UUID repositoryId,
                                           @RequestParam(required = false) UUID templateId,
                                           @RequestParam(required = false) String severity,
                                           @RequestParam(required = false) String docType,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedFrom,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedTo,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(required = false) String direction) {
        return reviewService.pendingOnMe(CurrentUser.requireId(), workspaceId,
                new ReviewQueueFilter(repositoryId, templateId, severity, docType,
                        submittedFrom, submittedTo, sort, direction));
    }

    @GetMapping("/workspaces/{workspaceId}/reviews/staff-queue")
    @Operation(summary = "Versions awaiting a first-tier (Staff) review")
    public List<ReviewQueueItemResponse> staffQueue(@PathVariable UUID workspaceId,
                                                     @RequestParam(required = false) UUID repositoryId,
                                                     @RequestParam(required = false) UUID templateId,
                                                     @RequestParam(required = false) String severity,
                                                     @RequestParam(required = false) String docType,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedFrom,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedTo,
                                                     @RequestParam(required = false) String sort,
                                                     @RequestParam(required = false) String direction) {
        return reviewService.staffQueue(CurrentUser.requireId(), workspaceId,
                new ReviewQueueFilter(repositoryId, templateId, severity, docType,
                        submittedFrom, submittedTo, sort, direction));
    }

    @GetMapping("/workspaces/{workspaceId}/reviews/manager-queue")
    @Operation(summary = "Versions awaiting final (Manager) approval")
    public List<ReviewQueueItemResponse> managerQueue(@PathVariable UUID workspaceId,
                                                       @RequestParam(required = false) UUID repositoryId,
                                                       @RequestParam(required = false) UUID templateId,
                                                       @RequestParam(required = false) String severity,
                                                       @RequestParam(required = false) String docType,
                                                       @RequestParam(required = false)
                                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedFrom,
                                                       @RequestParam(required = false)
                                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedTo,
                                                       @RequestParam(required = false) String sort,
                                                       @RequestParam(required = false) String direction) {
        return reviewService.managerQueue(CurrentUser.requireId(), workspaceId,
                new ReviewQueueFilter(repositoryId, templateId, severity, docType,
                        submittedFrom, submittedTo, sort, direction));
    }

    @GetMapping("/workspaces/{workspaceId}/reviews/publish-queue")
    @Operation(summary = "Versions approved but not yet published")
    public List<ReviewQueueItemResponse> publishQueue(@PathVariable UUID workspaceId,
                                                        @RequestParam(required = false) UUID repositoryId,
                                                        @RequestParam(required = false) UUID templateId,
                                                        @RequestParam(required = false) String severity,
                                                        @RequestParam(required = false) String docType,
                                                        @RequestParam(required = false)
                                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedFrom,
                                                        @RequestParam(required = false)
                                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedTo,
                                                        @RequestParam(required = false) String sort,
                                                        @RequestParam(required = false) String direction) {
        return reviewService.publishQueue(CurrentUser.requireId(), workspaceId,
                new ReviewQueueFilter(repositoryId, templateId, severity, docType,
                        submittedFrom, submittedTo, sort, direction));
    }

    /**
     * The versions the caller submitted for review, with the verdict
     * they received.
     *
     * <p>Available to every workspace member — an author without any
     * reviewing role still needs to see what happened to the draft they
     * handed over.
     */
    @GetMapping("/workspaces/{workspaceId}/reviews/my-submissions")
    @Operation(summary = "Versions the caller submitted for review and their current verdict")
    public List<SubmittedVersionResponse> mySubmissions(@PathVariable UUID workspaceId) {
        return reviewService.mySubmissions(CurrentUser.requireId(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/capabilities")
    @Operation(summary = "Effective capabilities of the caller in this workspace")
    public AuthorizationService.Capabilities capabilities(@PathVariable UUID workspaceId) {
        return authorizationService.capabilities(CurrentUser.requireId(), workspaceId);
    }
}
