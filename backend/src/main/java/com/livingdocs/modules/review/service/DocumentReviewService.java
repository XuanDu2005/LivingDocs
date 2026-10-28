package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.review.dto.DocumentReviewResponse;
import com.livingdocs.modules.review.dto.ReviewQueueItemResponse;
import com.livingdocs.modules.review.dto.ReviewQueueResponse;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;
import com.livingdocs.modules.review.repository.DocumentReviewRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the two-tier governance workflow:
 * <ol>
 *     <li><b>Staff (Review)</b> — first-level review, technical correctness.</li>
 *     <li><b>Manager (Approve)</b> — final approval, controls publishing.</li>
 * </ol>
 *
 * <p>A version is only eligible for Manager approval once a Staff review has
 * approved it. Once approved, a Manager can publish or roll back the
 * document.
 *
 * <h2>Who may do what</h2>
 * Enforcement is delegated to {@link AuthorizationService}, which combines
 * the <em>platform role</em> axis (DEVELOPER / STAFF / TECHNICAL_LEAD /
 * MANAGER / ADMIN) with the <em>workspace role</em> axis (MEMBER / MANAGER).
 * Both must be satisfied. {@code TECHNICAL_LEAD} can act at either tier —
 * it is deliberately able to both review and approve.
 */
@Service
public class DocumentReviewService {

    private static final Logger log = LoggerFactory.getLogger(DocumentReviewService.class);

    /** Tier label recorded on the review row. */
    public static final String TIER_STAFF = "STAFF";
    public static final String TIER_MANAGER = "MANAGER";

    private final DocumentReviewRepository reviewRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;
    private final AuthorizationService authorizationService;

    public DocumentReviewService(DocumentReviewRepository reviewRepository,
                                 DocumentVersionRepository versionRepository,
                                 DocumentRepository documentRepository,
                                 WorkspaceService workspaceService,
                                 AuditLogService auditLogService,
                                 AuthorizationService authorizationService) {
        this.reviewRepository = reviewRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
        this.authorizationService = authorizationService;
    }

    /**
     * Staff submits a review verdict on a pending version.
     *
     * <p>Enforcement: the actor needs a platform role that can perform
     * the first-tier review ({@code STAFF}, {@code TECHNICAL_LEAD},
     * {@code MANAGER}, {@code ADMIN}) <em>and</em> workspace membership.
     */
    @Transactional
    public DocumentReviewResponse submitStaffReview(UUID actorId, UUID documentId,
                                                     UUID versionId, SubmitReviewRequest req) {
        Document document = loadAndAuthorize(actorId, documentId);
        authorizationService.requireStaffReviewer(actorId, document.getWorkspaceId());
        DocumentVersion version = loadVersion(documentId, versionId);
        if (version.getStatus() != VersionStatus.PENDING && version.getStatus() != VersionStatus.IN_REVIEW) {
            throw new BadRequestException("Only pending or in-review versions can be reviewed");
        }
        DocumentReview review = new DocumentReview(
                version.getId(), actorId, TIER_STAFF, req.decision(), req.comment()
        );
        DocumentReview saved = reviewRepository.save(review);

        applyStaffVerdict(document, version, req.decision());
        auditLogService.record(actorId, actorTierLabel(actorId, TIER_STAFF),
                "document.review", "document_version", saved.getId().toString(),
                document.getWorkspaceId(),
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId.toString(),
                        "decision", req.decision().name()));
        log.info("Staff review submitted: document={} version={} decision={}",
                documentId, version.getVersionNumber(), req.decision());
        return DocumentReviewResponse.from(saved);
    }

    /**
     * Manager grants (or denies) final approval on a version.
     *
     * <p>Enforcement: the actor needs a platform role that can approve
     * ({@code TECHNICAL_LEAD}, {@code MANAGER}, {@code ADMIN}) <em>and</em>
     * workspace membership.
     */
    @Transactional
    public DocumentReviewResponse submitManagerDecision(UUID actorId, UUID documentId,
                                                       UUID versionId, SubmitReviewRequest req) {
        Document document = loadAndAuthorize(actorId, documentId);
        authorizationService.requireManagerApprover(actorId, document.getWorkspaceId());
        DocumentVersion version = loadVersion(documentId, versionId);

        if (req.decision() == ReviewDecision.APPROVED) {
            // Staff must have approved first.
            boolean staffApproved = reviewRepository
                    .findAllByDocumentVersionIdAndReviewerRoleOrderByDecidedAtDesc(version.getId(), TIER_STAFF)
                    .stream().anyMatch(r -> r.getDecision() == ReviewDecision.APPROVED);
            if (!staffApproved) {
                throw new BadRequestException("Manager cannot approve before Staff approves");
            }
        }

        DocumentReview review = new DocumentReview(
                version.getId(), actorId, TIER_MANAGER, req.decision(), req.comment()
        );
        DocumentReview saved = reviewRepository.save(review);

        applyManagerVerdict(document, version, req.decision());
        auditLogService.record(actorId, actorTierLabel(actorId, TIER_MANAGER),
                "document.approval", "document_version", saved.getId().toString(),
                document.getWorkspaceId(),
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId.toString(),
                        "decision", req.decision().name()));
        log.info("Manager decision submitted: document={} version={} decision={}",
                documentId, version.getVersionNumber(), req.decision());
        return DocumentReviewResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DocumentReviewResponse> reviewsForVersion(UUID actorId, UUID documentId, UUID versionId) {
        Document document = loadAndAuthorize(actorId, documentId);
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        DocumentVersion v = loadVersion(documentId, versionId);
        return reviewRepository.findAllByDocumentVersionIdOrderByDecidedAtDesc(v.getId())
                .stream().map(DocumentReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentReviewResponse> reviewsForReviewer(UUID actorId) {
        return reviewRepository.findAllByReviewerUserIdOrderByDecidedAtDesc(actorId)
                .stream().map(DocumentReviewResponse::from).toList();
    }

    // -----------------------------------------------------------------
    // review queues
    // -----------------------------------------------------------------

    /**
     * Everything the caller may act on in this workspace, split by tier.
     *
     * <p>The returned {@link ReviewQueueResponse} carries three buckets:
     * <ul>
     *   <li>{@code staffQueue} — versions in {@link VersionStatus#PENDING}
     *       awaiting a first-tier review.</li>
     *   <li>{@code managerQueue} — versions in {@link VersionStatus#IN_REVIEW}
     *       that a Staff reviewer already approved, awaiting final
     *       approval.</li>
     *   <li>{@code publishQueue} — versions in {@link VersionStatus#APPROVED}
     *       that can be promoted to head.</li>
     * </ul>
     *
     * <p>Buckets the caller has no capability for come back empty rather
     * than forbidden, so the UI can render a single page with whatever
     * sections apply to the user.
     */
    @Transactional(readOnly = true)
    public ReviewQueueResponse pendingOnMe(UUID actorId, UUID workspaceId) {
        authorizationService.requireWorkspaceMember(actorId, workspaceId);
        AuthorizationService.Capabilities caps = authorizationService.capabilities(actorId, workspaceId);

        List<ReviewQueueItemResponse> staffQueue = new ArrayList<>();
        List<ReviewQueueItemResponse> managerQueue = new ArrayList<>();
        List<ReviewQueueItemResponse> publishQueue = new ArrayList<>();

        if (caps.canStaffReview()) {
            staffQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.PENDING, "STAFF_REVIEW"));
        }
        if (caps.canManagerApprove()) {
            managerQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.IN_REVIEW, "MANAGER_APPROVAL"));
            publishQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.APPROVED, "PUBLISH"));
        }

        return new ReviewQueueResponse(
                workspaceId,
                caps.platformRoles(),
                caps.member(),
                caps.workspaceManager(),
                caps.canStaffReview(),
                caps.canManagerApprove(),
                staffQueue,
                managerQueue,
                publishQueue);
    }

    /**
     * All items waiting on a first-tier review in the workspace, regardless
     * of who will review them. Requires Staff-tier capability.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemResponse> staffQueue(UUID actorId, UUID workspaceId) {
        authorizationService.requireStaffReviewer(actorId, workspaceId);
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.PENDING, "STAFF_REVIEW");
    }

    /**
     * All items waiting on final approval in the workspace. Requires
     * Manager-tier capability.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemResponse> managerQueue(UUID actorId, UUID workspaceId) {
        authorizationService.requireManagerApprover(actorId, workspaceId);
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.IN_REVIEW, "MANAGER_APPROVAL");
    }

    /**
     * Versions that were approved but not yet published.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemResponse> publishQueue(UUID actorId, UUID workspaceId) {
        authorizationService.requireManagerApprover(actorId, workspaceId);
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.APPROVED, "PUBLISH");
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * Load every version of every document in the workspace that currently
     * sits in the given status, and project it into a queue row.
     */
    private List<ReviewQueueItemResponse> itemsForWorkspaceStatus(UUID workspaceId,
                                                                   VersionStatus status,
                                                                   String action) {
        List<Document> documents = documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
        List<ReviewQueueItemResponse> out = new ArrayList<>();
        for (Document doc : documents) {
            for (DocumentVersion v : versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(doc.getId())) {
                if (v.getStatus() != status) {
                    continue;
                }
                out.add(new ReviewQueueItemResponse(
                        v.getId(),
                        doc.getId(),
                        workspaceId,
                        doc.getTitle(),
                        doc.getSlug(),
                        doc.getDocType(),
                        v.getVersionNumber(),
                        v.getStatus(),
                        v.getConfidenceScore(),
                        v.getActorRole() != null ? v.getActorRole().name() : null,
                        v.getChangeSummary(),
                        action,
                        v.getCreatedAt()));
            }
        }
        return out;
    }

    private Document loadAndAuthorize(UUID actorId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        return document;
    }

    private DocumentVersion loadVersion(UUID documentId, UUID versionId) {
        DocumentVersion v = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!v.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }
        return v;
    }

    /**
     * The tier label used in audit records. Records the platform role that
     * actually performed the action, so the audit trail shows whether a
     * Technical Lead acted as Staff or as Manager.
     */
    private String actorTierLabel(UUID actorId, String tier) {
        var roles = authorizationService.platformRoles(actorId);
        for (String code : roles) {
            if (AuthorizationService.ROLE_TECHNICAL_LEAD.equals(code)
                    || AuthorizationService.ROLE_ADMIN.equals(code)) {
                return code + "@" + tier;
            }
        }
        return tier;
    }

    private void applyStaffVerdict(Document document, DocumentVersion version, ReviewDecision decision) {
        switch (decision) {
            case APPROVED -> {
                version.setStatus(VersionStatus.IN_REVIEW);
                document.setStatus(DocumentStatus.IN_REVIEW);
            }
            case REJECTED, REQUEST_CHANGES -> {
                version.setStatus(VersionStatus.REJECTED);
                document.setStatus(DocumentStatus.REJECTED);
            }
            case COMMENT -> {
                // Comment doesn't change workflow state.
            }
        }
    }

    private void applyManagerVerdict(Document document, DocumentVersion version, ReviewDecision decision) {
        switch (decision) {
            case APPROVED -> {
                version.setStatus(VersionStatus.APPROVED);
                document.setStatus(DocumentStatus.APPROVED);
            }
            case REJECTED, REQUEST_CHANGES -> {
                version.setStatus(VersionStatus.REJECTED);
                document.setStatus(DocumentStatus.REJECTED);
            }
            case COMMENT -> {
                // No state transition.
            }
        }
    }

    /**
     * Used internally by AI workflows when a draft is submitted. Marks the
     * version PENDING so it appears in the Staff review queue.
     */
    @Transactional
    public void markPendingForStaffReview(UUID documentId, UUID versionId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        DocumentVersion v = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!v.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }
        v.setStatus(VersionStatus.PENDING);
        document.setStatus(DocumentStatus.IN_REVIEW);
    }
}
