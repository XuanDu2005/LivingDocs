package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.notification.model.NotificationKinds;
import com.livingdocs.modules.notification.service.NotificationService;
import com.livingdocs.modules.review.dto.DocumentReviewResponse;
import com.livingdocs.modules.review.dto.ReviewQueueItemResponse;
import com.livingdocs.modules.review.dto.ReviewQueueResponse;
import com.livingdocs.modules.review.dto.SubmittedVersionResponse;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;
import com.livingdocs.modules.review.repository.DocumentReviewRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final NotificationService notificationService;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public DocumentReviewService(DocumentReviewRepository reviewRepository,
                                 DocumentVersionRepository versionRepository,
                                 DocumentRepository documentRepository,
                                 WorkspaceService workspaceService,
                                 AuditLogService auditLogService,
                                 AuthorizationService authorizationService,
                                 NotificationService notificationService,
                                 WorkspaceMemberRepository workspaceMemberRepository,
                                 UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
        this.authorizationService = authorizationService;
        this.notificationService = notificationService;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
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
        notifySubmitter(document, version, req, saved.getReviewerRole());
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
        notifySubmitter(document, version, req, saved.getReviewerRole());
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
                publishQueue,
                mySubmissions(actorId, workspaceId));
    }

    /**
     * The versions the caller submitted for review, together with the
     * verdict they received.
     *
     * <p>This is the author-side counterpart of the three review queues.
     * Every reviewer also sees their own submissions here, so the page
     * can show "what is waiting for me" and "what did I hand over" in
     * one place.
     *
     * <p>Requires workspace membership — the list is scoped to one
     * workspace and never leaks across workspaces.
     */
    @Transactional(readOnly = true)
    public List<SubmittedVersionResponse> mySubmissions(UUID actorId, UUID workspaceId) {
        authorizationService.requireWorkspaceMember(actorId, workspaceId);

        List<Document> documents = documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<UUID, Document> byId = new HashMap<>();
        for (Document doc : documents) {
            byId.put(doc.getId(), doc);
        }

        List<DocumentVersion> versions =
                versionRepository.findAllBySubmittedByUserIdAndDocumentIdInOrderBySubmittedAtDesc(
                        actorId, byId.keySet());
        if (versions.isEmpty()) {
            return List.of();
        }

        // One query for every review of every submitted version, instead
        // of one query per version.
        List<UUID> versionIds = versions.stream().map(DocumentVersion::getId).toList();
        Map<UUID, DocumentReview> latestReview = new HashMap<>();
        for (DocumentReview review : reviewRepository.findAllByDocumentVersionIdInOrderByDecidedAtDesc(versionIds)) {
            // The repository returns newest first, so the first hit wins.
            latestReview.putIfAbsent(review.getDocumentVersionId(), review);
        }

        List<SubmittedVersionResponse> out = new ArrayList<>(versions.size());
        for (DocumentVersion version : versions) {
            Document doc = byId.get(version.getDocumentId());
            if (doc == null) {
                continue;
            }
            DocumentReview review = latestReview.get(version.getId());
            out.add(new SubmittedVersionResponse(
                    version.getId(),
                    doc.getId(),
                    doc.getTitle(),
                    doc.getDocType(),
                    version.getVersionNumber(),
                    version.getStatus(),
                    version.getChangeSummary(),
                    version.getConfidenceScore(),
                    version.getSubmittedAt(),
                    stageOf(version, review),
                    review != null ? review.getDecision().name() : null,
                    review != null ? review.getReviewerRole() : null,
                    review != null ? reviewerName(review.getReviewerUserId()) : null,
                    review != null ? review.getComment() : null,
                    review != null ? review.getDecidedAt() : null));
        }
        return out;
    }

    /**
     * Flatten the workflow state of a submitted version into a single
     * label the UI can render.
     *
     * <p>The version status alone is not enough: a version that was
     * sent back for changes and one that was rejected both end up as
     * {@link VersionStatus#REJECTED}, but the author needs to know
     * whether the reviewer will look at it again.
     */
    private String stageOf(DocumentVersion version, DocumentReview latestReview) {
        String decision = latestReview != null ? latestReview.getDecision().name() : null;
        return switch (version.getStatus()) {
            case PENDING -> "WAITING_STAFF";
            case IN_REVIEW -> "WAITING_MANAGER";
            case APPROVED -> "APPROVED";
            case PUBLISHED -> "PUBLISHED";
            case REJECTED -> "REQUEST_CHANGES".equals(decision) ? "CHANGES_REQUESTED" : "REJECTED";
            case DRAFT -> "DRAFT";
            case SUPERSEDED -> "SUPERSEDED";
        };
    }

    /**
     * The display name of a reviewer, so the author sees <em>who</em>
     * made the call. Falls back to null when the reviewer account is
     * gone.
     */
    private String reviewerName(UUID reviewerUserId) {
        if (reviewerUserId == null) {
            return null;
        }
        return userRepository.findById(reviewerUserId)
                .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                        ? u.getDisplayName()
                        : u.getEmail())
                .orElse(null);
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
     * Push a draft version into the Staff review queue.
     *
     * <p>A version is created as a {@link VersionStatus#DRAFT}. The
     * author decides when it is ready for a reviewer; only then does it
     * become {@link VersionStatus#PENDING} and show up in the queue.
     *
     * <p>Only a workspace member may submit, and only for a document in
     * their workspace.
     *
     * @throws BadRequestException when the version is not a draft
     */
    @Transactional
    public DocumentVersion submitForReview(UUID actorId, UUID documentId, UUID versionId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());

        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!version.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new BadRequestException(
                    "Only draft versions can be submitted for review (current: "
                            + version.getStatus() + ")");
        }

        version.setStatus(VersionStatus.PENDING);
        // Remember who handed the version over. Without this the author
        // cannot find their own submissions later, and the review queue
        // has no author-side view at all.
        version.setSubmittedByUserId(actorId);
        version.setSubmittedAt(OffsetDateTime.now());
        versionRepository.save(version);
        document.setStatus(DocumentStatus.IN_REVIEW);
        documentRepository.save(document);

        auditLogService.record(actorId, "USER", "document.submitted_for_review",
                "document_version", versionId.toString(), document.getWorkspaceId(),
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId.toString(),
                        "versionNumber", version.getVersionNumber()));

        notifyReviewers(document, version, actorId);

        log.info("Version submitted for review: document={} version={}",
                documentId, version.getVersionNumber());
        return version;
    }

    /**
     * Tell the author of a version that a reviewer made a call on it.
     *
     * <p>The author is the person who pushed the version into the queue
     * ({@code submittedByUserId}). Without this the only way to learn
     * that your draft was rejected is to keep reloading the review queue
     * page — and a developer, whose entire workflow is handing drafts
     * over, has no reason to visit that page at all.
     *
     * <p>A failed notification must never abort the review itself: the
     * verdict is already recorded.
     */
    private void notifySubmitter(Document document, DocumentVersion version,
                                 SubmitReviewRequest req, String reviewerRole) {
        UUID submitter = version.getSubmittedByUserId();
        if (submitter == null) {
            return;
        }
        try {
            String title = "Review decision: " + document.getTitle();
            String body = "Version " + version.getVersionNumber() + " — " + decisionLabel(req.decision());
            if (req.comment() != null && !req.comment().isBlank()) {
                body = body + ": " + req.comment().trim();
            }
            String link = "/workspaces/" + document.getWorkspaceId()
                    + "/documents/" + document.getId();
            notificationService.sendBatch(
                    document.getWorkspaceId(), List.of(submitter),
                    NotificationKinds.REVIEW_DECIDED, title, body, link);
        } catch (Exception e) {
            log.warn("Failed to notify the submitter of document {} version {}: {}",
                    document.getTitle(), version.getVersionNumber(), e.getMessage());
        }
    }

    /**
     * A human-readable label for a review verdict, used in notifications.
     */
    private String decisionLabel(ReviewDecision decision) {
        return switch (decision) {
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case REQUEST_CHANGES -> "changes requested";
            case COMMENT -> "commented";
        };
    }

    /**
     * Tell the people who can act on this version that it is waiting.
     *
     * <p>The Staff tier is notified first — they own the first review
     * step. Once a Staff reviewer has approved it, the Manager tier is
     * notified instead. The author's own notification preference
     * ("review requested") applies to them as well, so a reviewer who
     * turned it off is not notified twice.
     */
    private void notifyReviewers(Document document, DocumentVersion version, UUID actorId) {
        try {
            List<UUID> recipients = reviewersFor(document.getWorkspaceId());
            // The author is not told "a review was requested" for their
            // own submission — they know.
            recipients = recipients.stream()
                    .filter(id -> !id.equals(actorId))
                    .toList();
            if (recipients.isEmpty()) {
                return;
            }
            String title = "Review requested: " + document.getTitle();
            String body = "Version " + version.getVersionNumber() + " is waiting for review.";
            String link = "/workspaces/" + document.getWorkspaceId()
                    + "/documents/" + document.getId() + "/reviews";
            int sent = notificationService.sendBatch(
                    document.getWorkspaceId(), recipients, "review.requested", title, body, link);
            log.info("Notified {} reviewer(s) about document {} version {}",
                    sent, document.getTitle(), version.getVersionNumber());
        } catch (Exception e) {
            // A failed notification must not abort the submission — the
            // version is already in the queue and the reviewer will see
            // it there.
            log.warn("Failed to notify reviewers about document {}: {}",
                    document.getTitle(), e.getMessage());
        }
    }

    /**
     * The workspace members who can review at the Staff tier.
     */
    private List<UUID> reviewersFor(UUID workspaceId) {
        List<UUID> out = new ArrayList<>();
        for (WorkspaceMember member : workspaceMemberRepository.findAllByWorkspaceId(workspaceId)) {
            UUID userId = member.getUserId();
            if (userId == null) {
                continue;
            }
            try {
                if (authorizationService.capabilities(userId, workspaceId).canStaffReview()) {
                    out.add(userId);
                }
            } catch (Exception ignored) {
                // A member whose roles could not be resolved is simply
                // not notified.
            }
        }
        return out;
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
