package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.common.text.MarkdownSections;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.notification.model.NotificationKinds;
import com.livingdocs.modules.notification.service.NotificationService;
import com.livingdocs.modules.review.dto.DocumentReviewResponse;
import com.livingdocs.modules.review.dto.ReviewQueueFilter;
import com.livingdocs.modules.review.dto.ReviewQueueItemResponse;
import com.livingdocs.modules.review.dto.ReviewQueueResponse;
import com.livingdocs.modules.review.dto.ReviseVersionRequest;
import com.livingdocs.modules.review.dto.SubmittedVersionResponse;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;
import com.livingdocs.modules.review.repository.DocumentReviewRepository;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.user.model.User;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final PermissionService permissionService;
    private final NotificationService notificationService;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final DriftAlertRepository driftRepository;
    private final DocTemplateRepository templateRepository;
    private final DocTemplateService templateService;
    private final TemplateComplianceService complianceService;

    public DocumentReviewService(DocumentReviewRepository reviewRepository,
                                 DocumentVersionRepository versionRepository,
                                 DocumentRepository documentRepository,
                                 WorkspaceService workspaceService,
                                 AuditLogService auditLogService,
                                 AuthorizationService authorizationService,
                                 PermissionService permissionService,
                                 NotificationService notificationService,
                                 WorkspaceMemberRepository workspaceMemberRepository,
                                 UserRepository userRepository,
                                 DriftAlertRepository driftRepository,
                                 DocTemplateRepository templateRepository,
                                 DocTemplateService templateService,
                                 TemplateComplianceService complianceService) {
        this.reviewRepository = reviewRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
        this.authorizationService = authorizationService;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.driftRepository = driftRepository;
        this.templateRepository = templateRepository;
        this.templateService = templateService;
        this.complianceService = complianceService;
    }

    /**
     * Staff submits a review verdict on a pending version.
     *
     * <p>Enforcement: the actor needs the {@code DOCUMENT_VERSION_REVIEW}
     * permission (resolved from the permission matrix, so an administrator
     * can revoke it at runtime) <em>and</em> workspace membership.
     *
     * <p>Self-review is refused: a reviewer may not act on a version they
     * submitted themselves. That rule is enforced regardless of the
     * decision, so the reviewer cannot launder an approval by first
     * leaving a comment on their own submission.
     */
    @Transactional
    public DocumentReviewResponse submitStaffReview(UUID actorId, UUID documentId,
                                                     UUID versionId, SubmitReviewRequest req) {
        Document document = loadAndAuthorize(actorId, documentId);
        authorizationService.requireDocumentReviewer(actorId, document.getWorkspaceId());
        // Take a write lock on the version: two Staff reviewers acting
        // on the same version at the same time must not both write a
        // verdict (S7).
        DocumentVersion version = loadVersionForUpdate(documentId, versionId);
        if (version.getStatus() != VersionStatus.PENDING && version.getStatus() != VersionStatus.IN_REVIEW) {
            throw new BadRequestException("Only pending or in-review versions can be reviewed");
        }
        // Self-review guard. Without it a Staff member could submit their
        // own draft and approve it, forwarding it to the Manager tier
        // under their own name — the two-tier workflow would collapse into
        // one person checking their own work.
        UUID submitter = version.getSubmittedByUserId();
        if (submitter != null && submitter.equals(actorId)) {
            throw new ForbiddenException("You cannot review a version you submitted yourself");
        }
        var section = resolveSection(version, req);
        DocumentReview review = new DocumentReview(
                version.getId(), actorId, TIER_STAFF, req.decision(), req.comment(),
                section != null ? section.slug() : null,
                section != null ? section.heading() : null,
                section != null ? section.anchor() : null
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
        // A Staff approval hands the version over to the Manager tier,
        // so that tier is notified here (S12).
        if (req.decision() == ReviewDecision.APPROVED) {
            notifyManagerTier(document, version, actorId);
        }
        return DocumentReviewResponse.from(saved);
    }

    /**
     * Manager grants (or denies) final approval on a version.
     *
     * <p>Enforcement: the actor needs the {@code DOCUMENT_VERSION_APPROVE}
     * permission (resolved from the permission matrix) <em>and</em>
     * workspace membership.
     */
    @Transactional
    public DocumentReviewResponse submitManagerDecision(UUID actorId, UUID documentId,
                                                       UUID versionId, SubmitReviewRequest req) {
        Document document = loadAndAuthorize(actorId, documentId);
        authorizationService.requireDocumentApprover(actorId, document.getWorkspaceId());
        DocumentVersion version = loadVersionForUpdate(documentId, versionId);

        if (version.getStatus() != VersionStatus.IN_REVIEW && version.getStatus() != VersionStatus.APPROVED) {
            throw new BadRequestException(
                    "Only versions that Staff already approved can be decided (current: "
                            + version.getStatus() + ")");
        }

        if (req.decision() == ReviewDecision.APPROVED) {
            // The *latest* Staff verdict must be an approval. Checking
            // "any Staff review ever approved" was wrong: a reviewer who
            // approved and then asked for changes left the version in a
            // state the Manager was not meant to see.
            DocumentReview latestStaff = latestStaffReview(version.getId());
            boolean staffApproved = latestStaff != null
                    && latestStaff.getDecision() == ReviewDecision.APPROVED;
            if (!staffApproved) {
                throw new BadRequestException("Manager cannot approve before Staff approves");
            }
        }

        var section = resolveSection(version, req);
        DocumentReview review = new DocumentReview(
                version.getId(), actorId, TIER_MANAGER, req.decision(), req.comment(),
                section != null ? section.slug() : null,
                section != null ? section.heading() : null,
                section != null ? section.anchor() : null
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

    /**
 * Revise the version that is currently under review.
 *
 * <p>Requirement #7 of the STAFF role: "fix small errors while
 * reviewing". A reviewer who spots a typo should be able to correct it
 * without sending the document back and waiting for another round trip.
 *
 * <p>The revision creates a <em>new</em> version that takes the place
 * of the one under review:
 * <ul>
 *   <li>the new version keeps the original submitter and submission
 *       time — the revision is still the same submission, so the author
 *       still finds it under "My submissions";</li>
 *   <li>the new version stays {@link VersionStatus#PENDING}, because the
 *       revision still has to be reviewed;</li>
 *   <li>the version that was reviewed becomes
 *       {@link VersionStatus#SUPERSEDED} — the history stays
 *       append-only, nothing is overwritten;</li>
 *   <li>the document's published head is untouched: a revision is not a
 *       publication.</li>
 * </ul>
 *
 * <p>Guarded by {@code DOCUMENT_VERSION_REVIEW} (the reviewer tier)
 * <em>and</em> {@code DOCUMENT_UPDATE} (the ability to write
 * documentation at all) — a reviewer who cannot edit documents
 * anywhere else has no business editing them here either.
 */
@Transactional
public DocumentVersion revise(UUID actorId, UUID documentId, UUID versionId,
                                  ReviseVersionRequest req) {
    Document document = loadAndAuthorize(actorId, documentId);
    authorizationService.requireDocumentReviewer(actorId, document.getWorkspaceId());
    permissionService.requireAnyPermission(actorId, PermissionCodes.DOCUMENT_UPDATE);

    DocumentVersion version = loadVersionForUpdate(documentId, versionId);
    if (version.getStatus() != VersionStatus.PENDING) {
        throw new BadRequestException(
                "Only a version that is awaiting review can be revised (current: "
                        + version.getStatus() + ")");
    }

    int next = versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId)
            .map(v -> v.getVersionNumber() + 1)
            .orElse(1);

    DocumentVersion revised = new DocumentVersion(
            documentId,
            next,
            req.bodyMarkdown(),
            req.changeSummary() != null && !req.changeSummary().isBlank()
                    ? req.changeSummary().trim()
                    : "Revised during review (was version " + version.getVersionNumber() + ")",
            actorRoleFor(actorId, document.getWorkspaceId()),
            actorId,
            version.getSourceCommitSha(),
            version.getSourcePrId(),
            version.getTemplateId(),
            VersionStatus.PENDING,
            version.getConfidenceScore());
    revised.setGroundingJson(version.getGroundingJson());
    revised.setSourceTicketRef(version.getSourceTicketRef());
    // The revision is still the same submission.
    revised.setSubmittedByUserId(version.getSubmittedByUserId());
    revised.setSubmittedAt(version.getSubmittedAt());
    DocumentVersion saved = versionRepository.save(revised);

    version.setStatus(VersionStatus.SUPERSEDED);
    versionRepository.save(version);

    // The audit trail is anchored to the version that was under review,
    // because that is the row a reader will look for; the new version
    // goes into the details. The new version's own id is assigned by
    // the persistence layer, so it is read defensively.
    String newVersionId = saved.getId() != null ? saved.getId().toString() : "";
    auditLogService.record(actorId, actorTierLabel(actorId, TIER_STAFF),
            "document.review_revised", "document_version", version.getId().toString(),
            document.getWorkspaceId(),
            Map.of("documentId", documentId.toString(),
                    "supersededVersionId", version.getId().toString(),
                    "newVersionId", newVersionId,
                    "newVersionNumber", next));

    log.info("Version revised during review: document={} old_version={} new_version={}",
            documentId, version.getVersionNumber(), next);
    return saved;
}

/**
 * The {@link com.livingdocs.modules.version.model.ActorRole} recorded on
 * a version a reviewer produced.
 *
 * <p>Mirrors the attribution rules of the version service: a Manager or
 * Technical Lead acts as {@code MANAGER}, a Developer as
 * {@code DEVELOPER}, everybody else as {@code STAFF}.
 */
private com.livingdocs.modules.version.model.ActorRole actorRoleFor(UUID actorId, UUID workspaceId) {
    var roles = authorizationService.platformRoles(actorId);
    if (roles.contains(AuthorizationService.ROLE_ADMIN)
            || roles.contains(AuthorizationService.ROLE_MANAGER)
            || roles.contains(AuthorizationService.ROLE_TECHNICAL_LEAD)) {
        return com.livingdocs.modules.version.model.ActorRole.MANAGER;
    }
    if (roles.contains(AuthorizationService.ROLE_DEVELOPER)) {
        return com.livingdocs.modules.version.model.ActorRole.DEVELOPER;
    }
    return com.livingdocs.modules.version.model.ActorRole.STAFF;
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
        return pendingOnMe(actorId, workspaceId, null);
    }

    /**
     * The same queue, filtered and sorted.
     */
    @Transactional(readOnly = true)
    public ReviewQueueResponse pendingOnMe(UUID actorId, UUID workspaceId, ReviewQueueFilter filter) {
        authorizationService.requireWorkspaceMember(actorId, workspaceId);
        AuthorizationService.Capabilities caps = authorizationService.capabilities(actorId, workspaceId);

        List<ReviewQueueItemResponse> staffQueue = new ArrayList<>();
        List<ReviewQueueItemResponse> managerQueue = new ArrayList<>();
        List<ReviewQueueItemResponse> publishQueue = new ArrayList<>();

        if (caps.canStaffReview()) {
            staffQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.PENDING, "STAFF_REVIEW", filter));
        }
        if (caps.canManagerApprove()) {
            managerQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.IN_REVIEW, "MANAGER_APPROVAL", filter));
            publishQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.APPROVED, "PUBLISH", filter));
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
     * The display names of several reviewers at once.
     *
     * <p>The queue needs the name of every submitter. Resolving them
     * one at a time would mean one query per row, which on a workspace
     * with a hundred pending submissions is a hundred queries for a
     * column the reviewer only glances at.
     */
    private Map<UUID, String> reviewerNames(Collection<UUID> userIds) {
        Map<UUID, String> out = new HashMap<>();
        List<UUID> ids = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return out;
        }
        for (User user : userRepository.findAllById(ids)) {
            out.put(user.getId(),
                    user.getDisplayName() != null && !user.getDisplayName().isBlank()
                            ? user.getDisplayName()
                            : user.getEmail());
        }
        return out;
    }

    /**
     * All items waiting on a first-tier review in the workspace, regardless
     * of who will review them. Requires Staff-tier capability.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemResponse> staffQueue(UUID actorId, UUID workspaceId,
                                                 ReviewQueueFilter filter) {
        authorizationService.requireDocumentReviewer(actorId, workspaceId);
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.PENDING, "STAFF_REVIEW", filter);
    }

    /**
     * All items waiting on final approval in the workspace. Requires
     * Manager-tier capability.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemResponse> managerQueue(UUID actorId, UUID workspaceId,
                                                   ReviewQueueFilter filter) {
        authorizationService.requireDocumentApprover(actorId, workspaceId);
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.IN_REVIEW, "MANAGER_APPROVAL", filter);
    }

    /**
     * Versions that were approved but not yet published.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemResponse> publishQueue(UUID actorId, UUID workspaceId,
                                                  ReviewQueueFilter filter) {
        authorizationService.requireDocumentPublisher(actorId, workspaceId);
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.APPROVED, "PUBLISH", filter);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * Load every version of every document in the workspace that currently
     * sits in the given status, and project it into a queue row.
     *
     * <p>The previous implementation loaded every version of every document
     * of the workspace and filtered in memory — one query per document. It
     * now loads the relevant versions in a single query.
     *
     * <p>The extra context (drift severity, template compliance, submitter)
     * is what turns the queue from a list into a working surface: a
     * reviewer can see which submissions are risky before opening them.
     */
    private List<ReviewQueueItemResponse> itemsForWorkspaceStatus(UUID workspaceId,
                                                                   VersionStatus status,
                                                                   String action) {
        return itemsForWorkspaceStatus(workspaceId, status, action, null);
    }

    private List<ReviewQueueItemResponse> itemsForWorkspaceStatus(UUID workspaceId,
                                                                   VersionStatus status,
                                                                   String action,
                                                                   ReviewQueueFilter filter) {
        List<Document> documents = documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<UUID, Document> byId = new HashMap<>();
        for (Document doc : documents) {
            byId.put(doc.getId(), doc);
        }

        // One query for every candidate version, instead of one query
        // per document.
        List<DocumentVersion> versions = versionRepository
                .findAllByDocumentIdInAndStatusInOrderBySubmittedAtDesc(
                        byId.keySet(), List.of(status));

        // The drift alerts of the whole workspace, grouped by document.
        // One query, not one per document.
        Map<UUID, List<DriftAlert>> driftByDocument = new HashMap<>();
        for (DriftAlert alert : driftRepository
                .findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
                        workspaceId, DriftResolution.OPEN)) {
            driftByDocument.computeIfAbsent(alert.getDocumentId(), k -> new ArrayList<>()).add(alert);
        }

        // The templates of the workspace, so the queue can name them and
        // evaluate the compliance of every version.
        Map<UUID, DocTemplate> templates = new HashMap<>();
        for (DocTemplate t : templateRepository.findAllByWorkspaceIdOrderByDocTypeAscNameAsc(workspaceId)) {
            templates.put(t.getId(), t);
        }
        for (DocTemplate t : templateRepository.findAllByWorkspaceIdIsNullOrderByDocTypeAscNameAsc()) {
            templates.putIfAbsent(t.getId(), t);
        }

        List<ReviewQueueItemResponse> out = new ArrayList<>();

        // The names of every submitter, resolved in one query.
        Map<UUID, String> submitterNames = reviewerNames(
                versions.stream().map(DocumentVersion::getSubmittedByUserId).toList());

        for (DocumentVersion v : versions) {
            Document doc = byId.get(v.getDocumentId());
            if (doc == null) {
                continue;
            }
            if (!matchesDocumentFilters(doc, v, filter)) {
                continue;
            }
            List<DriftAlert> alerts = driftByDocument.getOrDefault(doc.getId(), List.of());
            String maxSeverity = maxSeverity(alerts);
            if (filter != null && filter.hasSeverity()) {
                // "Filter by severity" means "at least this bad".
                if (maxSeverity == null
                        || severityRank(maxSeverity) < severityRank(filter.severity())) {
                    continue;
                }
            }
            DocTemplate template = resolveQueueTemplate(doc, v, templates);

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
                    v.getCreatedAt(),
                    v.getSubmittedAt(),
                    v.getSubmittedByUserId(),
                    v.getSubmittedByUserId() != null
                            ? submitterNames.get(v.getSubmittedByUserId())
                            : null,
                    doc.getRepositoryId(),
                    template != null ? template.getId() : null,
                    template != null ? template.getName() : null,
                    maxSeverity,
                    alerts.size(),
                    template != null
                            ? complianceService.evaluate(template, v.getBodyMarkdown()).compliant()
                            : null));
        }
        return sortQueue(out, filter);
    }

    /**
     * The document-level filters of a queue: repository, template and
     * document type.
     */
    private boolean matchesDocumentFilters(Document doc, DocumentVersion version,
                                           ReviewQueueFilter filter) {
        if (filter == null) {
            return true;
        }
        if (filter.repositoryId() != null && !filter.repositoryId().equals(doc.getRepositoryId())) {
            return false;
        }
        if (filter.templateId() != null) {
            UUID versionTemplate = version.getTemplateId();
            UUID docTemplate = doc.getTemplateId();
            boolean hit = filter.templateId().equals(versionTemplate)
                    || filter.templateId().equals(docTemplate);
            if (!hit) {
                // The document may inherit the workspace default for its
                // type — compare against that too.
                DocTemplate resolved = templateService.resolveDefault(
                        doc.getWorkspaceId(), doc.getDocType());
                hit = resolved != null && filter.templateId().equals(resolved.getId());
            }
            if (!hit) {
                return false;
            }
        }
        if (filter.docType() != null && !filter.docType().isBlank()
                && !filter.docType().equalsIgnoreCase(doc.getDocType())) {
            return false;
        }
        if (filter.submittedFrom() != null
                && (version.getSubmittedAt() == null
                    || version.getSubmittedAt().isBefore(filter.submittedFrom()))) {
            return false;
        }
        if (filter.submittedTo() != null
                && (version.getSubmittedAt() == null
                    || version.getSubmittedAt().isAfter(filter.submittedTo()))) {
            return false;
        }
        return true;
    }

    /**
     * The template a queue row should be evaluated against.
     */
    private DocTemplate resolveQueueTemplate(Document doc, DocumentVersion version,
                                             Map<UUID, DocTemplate> templates) {
        if (version.getTemplateId() != null && templates.containsKey(version.getTemplateId())) {
            return templates.get(version.getTemplateId());
        }
        if (doc.getTemplateId() != null && templates.containsKey(doc.getTemplateId())) {
            return templates.get(doc.getTemplateId());
        }
        DocTemplate fallback = templateService.resolveDefault(doc.getWorkspaceId(), doc.getDocType());
        return fallback;
    }

    /**
     * The worst open drift severity of a document, or {@code null} when
     * it has no open alerts.
     */
    private String maxSeverity(List<DriftAlert> alerts) {
        String worst = null;
        for (DriftAlert alert : alerts) {
            String severity = alert.getSeverity() != null ? alert.getSeverity().name() : null;
            if (severity == null) {
                continue;
            }
            if (worst == null || severityRank(severity) > severityRank(worst)) {
                worst = severity;
            }
        }
        return worst;
    }

    /**
     * The rank of a drift severity, so they can be compared.
     */
    private int severityRank(String severity) {
        if (severity == null) {
            return -1;
        }
        return switch (severity.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "MEDIUM" -> 1;
            case "LOW" -> 0;
            default -> -1;
        };
    }

    /**
     * Sort a queue by the requested criterion.
     *
     * <p>The default order is by submission date — the queue answers
     * "what is waiting for me", and the oldest submission is the one
     * that has been waiting longest.
     */
    private List<ReviewQueueItemResponse> sortQueue(List<ReviewQueueItemResponse> items,
                                                    ReviewQueueFilter filter) {
        if (items.size() < 2) {
            return items;
        }
        ReviewQueueFilter.Sort sort = filter != null
                ? filter.sortOption()
                : ReviewQueueFilter.Sort.SUBMITTED_AT;
        boolean asc = filter != null && filter.isAscending();
        List<ReviewQueueItemResponse> sorted = new ArrayList<>(items);
        switch (sort) {
            case CONFIDENCE -> sorted.sort((a, b) -> {
                int cmp = compareNullableFloat(a.confidenceScore(), b.confidenceScore());
                return asc ? cmp : -cmp;
            });
            case SEVERITY -> sorted.sort((a, b) -> {
                int cmp = Integer.compare(severityRank(a.maxDriftSeverity()),
                        severityRank(b.maxDriftSeverity()));
                return asc ? cmp : -cmp;
            });
            case SUBMITTED_AT -> sorted.sort((a, b) -> {
                int cmp = compareNullableDate(a.submittedAt(), b.submittedAt());
                return asc ? cmp : -cmp;
            });
        }
        return sorted;
    }

    /**
     * Compare two nullable floats, treating {@code null} as "unknown"
     * and therefore last.
     */
    private int compareNullableFloat(Float a, Float b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return Float.compare(a, b);
    }

    /**
     * Compare two nullable dates, treating {@code null} as "never
     * submitted" and therefore last.
     */
    private int compareNullableDate(OffsetDateTime a, OffsetDateTime b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }

    private Document loadAndAuthorize(UUID actorId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        return document;
    }

    private DocumentVersion loadVersionForUpdate(UUID documentId, UUID versionId) {
        DocumentVersion v = versionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!v.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }
        return v;
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

/**
 * Resolve the section a review comment targets.
 *
 * <p>A comment may name a section of the version's body, or comment on
 * the document as a whole (no section). When a section is named it has
 * to exist in the body: a comment anchored to a heading that is not
 * there would never show up anywhere, so the request is rejected
 * instead.
 *
 * @return the resolved heading, or {@code null} for a whole-document
 *     comment
 * @throws BadRequestException when the named heading does not exist in
 *     the version body
 */
    private MarkdownSections.Section resolveSection(DocumentVersion version, SubmitReviewRequest req) {
        boolean named = (req.sectionId() != null && !req.sectionId().isBlank())
                || (req.sectionHeading() != null && !req.sectionHeading().isBlank());
        if (!named) {
            return null;
        }
        List<MarkdownSections.Section> sections =
                MarkdownSections.parse(version.getBodyMarkdown());
        MarkdownSections.Section section =
                MarkdownSections.find(sections, req.sectionId(), req.sectionHeading());
        if (section == null) {
            throw new BadRequestException(
                    "The document has no section '" + req.sectionHeading() + "'");
        }
        return section;
    }

    /**
     * The most recent Staff-tier review of a version, or {@code null} when
     * no Staff member has looked at it yet.
 *
 * <p>The repository returns newest-first, so the first element is the
 * authoritative Staff verdict.
 */
    private DocumentReview latestStaffReview(UUID versionId) {
        List<DocumentReview> staffReviews = reviewRepository
                .findAllByDocumentVersionIdAndReviewerRoleOrderByDecidedAtDesc(versionId, TIER_STAFF);
        return staffReviews.isEmpty() ? null : staffReviews.get(0);
    }

    /**
     * Update the document status — but only when the version that carries
     * the verdict is still the newest one.
     *
     * <p>Without this check a verdict on an old version could move the
     * whole document backwards: a Staff member rejecting version 3 while
     * version 4 is already in review would mark the document REJECTED,
     * hiding the version that is actually moving forward.
     */
    private void syncDocumentStatus(Document document, DocumentVersion version, DocumentStatus status) {
        DocumentVersion latest = versionRepository
                .findFirstByDocumentIdOrderByVersionNumberDesc(document.getId())
                .orElse(null);
        if (latest == null || latest.getId().equals(version.getId())) {
            document.setStatus(status);
        }
    }

    private void applyStaffVerdict(Document document, DocumentVersion version, ReviewDecision decision) {
        switch (decision) {
            case APPROVED -> {
                version.setStatus(VersionStatus.IN_REVIEW);
                syncDocumentStatus(document, version, DocumentStatus.IN_REVIEW);
            }
            case REJECTED, REQUEST_CHANGES -> {
                version.setStatus(VersionStatus.REJECTED);
                syncDocumentStatus(document, version, DocumentStatus.REJECTED);
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
                syncDocumentStatus(document, version, DocumentStatus.APPROVED);
            }
            case REJECTED, REQUEST_CHANGES -> {
                version.setStatus(VersionStatus.REJECTED);
                syncDocumentStatus(document, version, DocumentStatus.REJECTED);
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
        // Only a move of the *newest* version may change the document
        // status — see {@link #syncDocumentStatus}.
        syncDocumentStatus(document, version, DocumentStatus.IN_REVIEW);
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
            List<UUID> recipients = staffReviewersFor(document.getWorkspaceId());
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
                    document.getWorkspaceId(), recipients, NotificationKinds.REVIEW_REQUESTED,
                    title, body, link);
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
     * Tell the Manager tier that a version is waiting for their decision.
     *
     * <p>Sent when a Staff reviewer approved the version. The author is
     * excluded — they already know, and they are told separately by
     * {@link #notifySubmitter}.
     */
    private void notifyManagerTier(Document document, DocumentVersion version, UUID actorId) {
        try {
            List<UUID> recipients = managerApproversFor(document.getWorkspaceId());
            recipients = recipients.stream()
                    .filter(id -> !id.equals(actorId))
                    .toList();
            if (recipients.isEmpty()) {
                return;
            }
            String title = "Approval requested: " + document.getTitle();
            String body = "Version " + version.getVersionNumber()
                    + " passed the Staff review and is waiting for final approval.";
            String link = "/workspaces/" + document.getWorkspaceId()
                    + "/documents/" + document.getId() + "/reviews";
            int sent = notificationService.sendBatch(
                    document.getWorkspaceId(), recipients, NotificationKinds.REVIEW_ASSIGNED,
                    title, body, link);
            log.info("Notified {} approver(s) about document {} version {}",
                    sent, document.getTitle(), version.getVersionNumber());
        } catch (Exception e) {
            log.warn("Failed to notify the manager tier about document {}: {}",
                    document.getTitle(), e.getMessage());
        }
    }

    /**
     * The workspace members who can review at the Staff tier.
     *
     * <p>Resolved from the permission matrix, not from a hard-coded role
     * list, so revoking {@code DOCUMENT_VERSION_REVIEW} also stops the
     * notifications.
     */
    private List<UUID> staffReviewersFor(UUID workspaceId) {
        return reviewersFor(workspaceId, PermissionCodes.DOCUMENT_VERSION_REVIEW);
    }

    /**
     * The workspace members who can approve at the Manager tier.
     */
    private List<UUID> managerApproversFor(UUID workspaceId) {
        return reviewersFor(workspaceId, PermissionCodes.DOCUMENT_VERSION_APPROVE);
    }

    /**
     * The workspace members who hold at least one of the given
     * permission codes.
     */
    private List<UUID> reviewersFor(UUID workspaceId, String permissionCode) {
        List<UUID> out = new ArrayList<>();
        for (WorkspaceMember member : workspaceMemberRepository.findAllByWorkspaceId(workspaceId)) {
            UUID userId = member.getUserId();
            if (userId == null) {
                continue;
            }
            try {
                if (permissionService.hasAnyPermission(userId, permissionCode)) {
                    out.add(userId);
                }
            } catch (Exception ignored) {
                // A member whose permissions could not be resolved is
                // simply not notified.
            }
        }
        return out;
    }

    /**
     * Used internally by AI workflows when a draft is submitted. Marks the
     * version PENDING so it appears in the Staff review queue.
     *
     * @deprecated The AI path deliberately does <em>not</em> push a
     *     generated version into the review queue on its own. Pushing
     *     every generated version automatically flooded reviewers with
     *     drafts nobody had looked at yet, and it also meant the author
     *     never saw the draft as a draft. The author submits it from the
     *     document page (see
     *     {@link #submitForReview(UUID, UUID, UUID)}), which is the only
     *     path that records {@code submittedByUserId} / {@code submittedAt}
     *     and therefore the only path that makes the submission visible
     *     under "My submissions".
     */
    @Deprecated
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
