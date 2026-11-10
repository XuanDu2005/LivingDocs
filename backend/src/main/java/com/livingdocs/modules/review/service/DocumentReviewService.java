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
    private final ReviewDelegationService delegationService;

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
                                 TemplateComplianceService complianceService,
                                 ReviewDelegationService delegationService) {
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
        this.delegationService = delegationService;
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
        // Reassignment guard. When a Manager delegated the Staff tier
        // of this workspace, the work is no longer theirs: they are not
        // notified about it either, so acting on it anyway would make
        // the delegation a no-op — the delegating Manager would simply
        // do the work they handed over.
        requireNotDelegatedAway(actorId, document.getWorkspaceId(), TIER_STAFF);
        // Take a write lock on the version: two Staff reviewers acting
        // on the same version at the same time must not both write a
        // verdict (S7).
        DocumentVersion version = loadVersionForUpdate(documentId, versionId);
        if (!isReviewable(version, TIER_STAFF, actorId, req.decision())) {
            throw new BadRequestException("Only pending or in-review versions can be reviewed");
        }
        // Self-review guard. Without it a Staff member could submit their
        // own draft and approve it, forwarding it to the Manager tier
        // under their own name — the two-tier workflow would collapse into
        // one person checking their own work. The guard yields only when
        // the workspace has nobody else who could review (see
        // {@link #requireNotSelfReview}).
        boolean selfReview = requireNotSelfReview(document, version, actorId, TIER_STAFF);
        var section = resolveSection(version, req);
        DocumentReview review = new DocumentReview(
                version.getId(), actorId, TIER_STAFF, req.decision(), req.comment(),
                section != null ? section.slug() : null,
                section != null ? section.heading() : null,
                section != null ? section.anchor() : null
        );
        // A verdict replaces the previous verdict of the same tier
        // instead of colliding with it. Before the superseded_at
        // column existed, recording a second verdict in the same tier
        // hit the partial unique index and crashed the request with a
        // 500 — so a reviewer who mis-clicked "Reject" had no way to
        // correct it. The old row stays in the trail, stamped as
        // superseded; only the current verdict counts from now on.
        DocumentReview superseded = supersedeCurrentVerdict(version.getId(), TIER_STAFF,
                req.decision());
        DocumentReview saved = reviewRepository.save(review);

        applyStaffVerdict(document, version, req.decision());
        auditLogService.record(actorId, actorTierLabel(actorId, TIER_STAFF),
                "document.review", "document_version", saved.getId().toString(),
                document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId.toString(),
                        "decision", req.decision().name(),
                        "selfReview", Boolean.toString(selfReview),
                        "supersededReviewId", superseded == null ? "" : superseded.getId().toString()));
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
        // Reassignment guard: a Manager who delegated the Manager tier
        // of this workspace does not approve it themselves any more.
        requireNotDelegatedAway(actorId, document.getWorkspaceId(), TIER_MANAGER);
        DocumentVersion version = loadVersionForUpdate(documentId, versionId);

        // The Manager tier owns a version that Staff already approved.
        // The exception is the same as on the Staff tier: a Manager who
        // mis-clicked "Reject" must be able to correct it, so a
        // REJECTED version carrying their own current verdict is still
        // correctable.
        if (!isReviewable(version, TIER_MANAGER, actorId, req.decision())) {
            throw new BadRequestException(
                    "Only versions that Staff already approved can be decided (current: "
                            + version.getStatus() + ")");
        }

        // Self-review guard on the approval tier. This one was missing
        // entirely, and it was the more dangerous half of the problem:
        // a Manager who submitted a document could approve it themselves
        // as soon as the Staff tier had approved it, so the final
        // sign-off — the one the whole two-tier workflow exists for —
        // could be given by the author. The guard yields only when the
        // workspace has nobody else who could approve (see
        // {@link #requireNotSelfReview}).
        boolean selfReview = requireNotSelfReview(document, version, actorId, TIER_MANAGER);

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
        // Same rule as the Staff tier: a new verdict replaces the
        // current one of the tier instead of colliding with it.
        DocumentReview superseded = supersedeCurrentVerdict(version.getId(), TIER_MANAGER,
                req.decision());
        DocumentReview saved = reviewRepository.save(review);

        applyManagerVerdict(document, version, req.decision());
        auditLogService.record(actorId, actorTierLabel(actorId, TIER_MANAGER),
                "document.approval", "document_version", saved.getId().toString(),
                document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId.toString(),
                        "decision", req.decision().name(),
                        "selfReview", Boolean.toString(selfReview),
                        "supersededReviewId", superseded == null ? "" : superseded.getId().toString()));
        log.info("Manager decision submitted: document={} version={} decision={}",
                documentId, version.getVersionNumber(), req.decision());
        notifySubmitter(document, version, req, saved.getReviewerRole());
        return DocumentReviewResponse.from(saved);
    }

    /**
     * The tiers the caller delegated away in this workspace.
     *
     * <p>A delegation that only redirected the notification would be
     * hollow: the delegating Manager would still be able to approve the
     * version, and would still have it in their queue. Handing work over
     * means the delegator steps aside.
     *
     * <p>Only the <em>delegating</em> Manager is affected. The delegate
     * may act (they hold the tier permission), and every other member
     * of the tier may act as before — the delegation routes the work,
     * it does not lock the tier down.
     */
    private List<String> delegatedTiersOf(UUID actorId, UUID workspaceId) {
        List<String> out = new ArrayList<>(2);
        if (delegationService.delegatorsFor(workspaceId, TIER_STAFF).contains(actorId)) {
            out.add(TIER_STAFF);
        }
        if (delegationService.delegatorsFor(workspaceId, TIER_MANAGER).contains(actorId)) {
            out.add(TIER_MANAGER);
        }
        return out;
    }

    /**
     * Refuse an action by somebody whose work of this tier was
     * delegated to somebody else.
     *
     * @see #delegatedTiersOf(UUID, UUID)
     */
    private void requireNotDelegatedAway(UUID actorId, UUID workspaceId, String tier) {
        if (delegatedTiersOf(actorId, workspaceId).contains(tier)) {
            throw new ForbiddenException(
                    "You delegated the " + tier + " tier of this workspace — "
                            + "the work belongs to your delegate");
        }
    }

    /**
     * Whether the actor may still record a verdict on this version.
     *
     * <p>Normally only a version that is {@code PENDING} or
     * {@code IN_REVIEW} can be reviewed — a version that was already
     * rejected or approved is history.
     *
     * <p>There is one exception, and it is the whole point of the
     * supersede mechanism: a reviewer who mis-clicked "Reject" has to
     * be able to correct it. A {@code REJECTED} version that carries a
     * <em>current verdict of this very reviewer</em> is still
     * correctable — otherwise the first mis-click would close the
     * version for good, and the correction would be impossible exactly
     * when it is needed.
     *
     * <p>The exception is deliberately narrow:
     * <ul>
     *   <li>it applies to a {@code REJECTED} version only — never to an
     *       approved or published one. An approval is not the
     *       reviewer's call to take back: it opened the publish step,
     *       and undoing that is a different act (a rollback);</li>
     *   <li>it applies to the reviewer's <em>own</em> current verdict
     *       only — another reviewer's rejection is not theirs to
     *       overwrite;</li>
     *   <li>a {@code COMMENT} is always allowed. A comment is a note,
     *       not an opinion about the content: it changes no workflow
     *       state, so blocking it would stop a reviewer from leaving
     *       a remark on a version somebody else already looked at.</li>
     * </ul>
     *
     * <p>The rule is the same on both tiers: a Staff reviewer who
     * mis-clicked "Reject" and a Manager who mis-clicked "Reject" are
     * in the same position.
     */
    private boolean isReviewable(DocumentVersion version, String tier, UUID actorId,
                                  ReviewDecision decision) {
        if (version.getStatus() == VersionStatus.PENDING
                || version.getStatus() == VersionStatus.IN_REVIEW) {
            return true;
        }
        if (version.getStatus() != VersionStatus.REJECTED) {
            return false;
        }
        // A COMMENT is a note, not an opinion about the content: it
        // changes no workflow state, so it must not be blocked just
        // because somebody else's verdict is the current one. A
        // reviewer may always add a note to a version that is still
        // open for review.
        if (decision == ReviewDecision.COMMENT) {
            return true;
        }
        // The reviewer may correct their own rejection, but only while
        // their verdict is still the current one — once somebody else
        // recorded a new opinion, the old verdict is history.
        List<DocumentReview> current = reviewRepository
                .findAllByDocumentVersionIdAndReviewerRoleAndSupersededAtIsNullOrderByDecidedAtDesc(
                        version.getId(), tier);
        for (DocumentReview review : current) {
            if (review.getDecision() == ReviewDecision.COMMENT) {
                continue;
            }
            return actorId.equals(review.getReviewerUserId());
        }
        return false;
    }

    /**
     * The self-review guard.
     *
     * <p>A reviewer may not act on a version they submitted themselves.
     * Without this rule the two-tier workflow collapses into one person
     * checking their own work: the submitter forwards their own draft
     * to the next tier under their own name, and the second tier sees a
     * document that already carries a verdict from the author.
     *
     * <h2>The single-reviewer escape hatch</h2>
     *
     * <p>A hard block has one failure mode that is worse than the
     * problem it solves: a workspace with exactly one person who may
     * review can never move a document forward. The submission sits in
     * the queue forever, and the only way out is for the author to
     * publish it — which is precisely what the guard was meant to
     * prevent.
     *
     * <p>So the guard yields when nobody else can act:
     * <ul>
     *   <li>the tier has exactly one eligible reviewer in the
     *       workspace, and that reviewer is the submitter;</li>
     *   <li>the act is recorded in the audit trail with the fact that
     *       it was a self-review, so the exception is visible to the
     *       people who audit the workspace rather than silent.</li>
     * </ul>
     *
     * <p>The escape hatch is deliberately narrow: it needs a workspace
     * with one eligible reviewer, not merely a workspace where the
     * author happens to be the first in line.
     */
    private boolean requireNotSelfReview(Document document, DocumentVersion version,
                                         UUID actorId, String tier) {
        UUID submitter = version.getSubmittedByUserId();
        if (submitter == null || !submitter.equals(actorId)) {
            return false;
        }
        if (!isSoleReviewer(document.getWorkspaceId(), tier, actorId)) {
            throw new ForbiddenException(
                    "You cannot review a version you submitted yourself");
        }
        // The exception is granted, but it is never silent: the audit
        // trail has to show that this verdict was a self-review.
        log.warn("Self-review allowed as the sole eligible reviewer: workspace={} tier={} "
                        + "actor={} document={} version={}",
                document.getWorkspaceId(), tier, actorId, document.getId(),
                version.getId());
        return true;
    }

    /**
     * Whether the actor is the only person in the workspace who could
     * act on the given tier.
     *
     * <p>The eligible reviewers are the workspace members that hold the
     * permission of the tier, minus the ones that delegated the tier
     * away — a Manager who handed the work over cannot act on it, so
     * they do not count as an eligible reviewer.
     *
     * <p>A delegation to somebody else counts: the delegate becomes the
     * eligible reviewer, so the tier is not sole-reviewer any more.
     */
    private boolean isSoleReviewer(UUID workspaceId, String tier, UUID actorId) {
        List<UUID> eligible = reviewersFor(workspaceId, permissionCodeFor(tier));
        List<UUID> delegates = delegationService.delegatesFor(workspaceId, tier);
        List<UUID> delegators = delegationService.delegatorsFor(workspaceId, tier);
        java.util.LinkedHashSet<UUID> pool = new java.util.LinkedHashSet<>(eligible);
        pool.addAll(delegates);
        pool.removeAll(delegators);
        pool.remove(actorId);
        return pool.isEmpty();
    }

    /**
     * The permission code that governs a tier.
     */
    private String permissionCodeFor(String tier) {
        return TIER_MANAGER.equals(tier)
                ? PermissionCodes.DOCUMENT_VERSION_APPROVE
                : PermissionCodes.DOCUMENT_VERSION_REVIEW;
    }

    /**
     * Supersede the current verdict of a tier, so a new verdict in that
     * tier does not collide with it.
     *
     * <p>Only a verdict that <em>carries an opinion</em> is superseded:
     * {@code APPROVED}, {@code REJECTED} and {@code REQUEST_CHANGES}.
     * A {@code COMMENT} is not an opinion about the content — it is a
     * note — so a comment never replaces a verdict and is never
     * replaced by one. Two comments from the same tier coexist, which
     * is what a reviewer expects when they leave several notes.
     *
     * <p>A verdict that is already superseded is left alone: the trail
     * should show when the opinion was actually replaced, not when it
     * was last touched.
     *
     * @return the verdict that was superseded, or {@code null} when
     *     there was nothing to supersede
     */
    private DocumentReview supersedeCurrentVerdict(UUID versionId, String tier,
                                                   ReviewDecision newDecision) {
        if (newDecision == ReviewDecision.COMMENT) {
            return null;
        }
        List<DocumentReview> current = reviewRepository
                .findAllByDocumentVersionIdAndReviewerRoleAndSupersededAtIsNullOrderByDecidedAtDesc(
                        versionId, tier);
        DocumentReview previous = null;
        for (DocumentReview review : current) {
            if (review.getDecision() == ReviewDecision.COMMENT) {
                // A comment is not an opinion — leave it in place.
                continue;
            }
            previous = review;
            break;
        }
        if (previous == null) {
            return null;
        }
        previous.supersede();
        reviewRepository.save(previous);
        // The UPDATE has to reach the database before the new verdict is
        // inserted.
        //
        // Hibernate flushes inserts *before* updates, so without this
        // flush the INSERT of the new verdict would be sent first and
        // would hit the partial unique index — the very collision this
        // mechanism exists to avoid. The request would fail with a 409
        // instead of recording the correction.
        reviewRepository.flush();
        log.info("Superseded previous {} verdict on version {} (review {})",
                tier, versionId, previous.getId());
        return previous;
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
            document.getWorkspaceId(), documentId,
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
        List<DocumentReview> reviews = reviewRepository
                .findAllByDocumentVersionIdOrderByDecidedAtDesc(v.getId());
        // Resolve the reviewer names in one query: the Manager tier reads
        // the Staff comments of a version and has to know who wrote them.
        Map<UUID, String> names = reviewerNames(
                reviews.stream().map(DocumentReview::getReviewerUserId).toList());
        return reviews.stream()
                .map(r -> DocumentReviewResponse.from(r, names.get(r.getReviewerUserId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentReviewResponse> reviewsForReviewer(UUID actorId) {
        List<DocumentReview> reviews = reviewRepository
                .findAllByReviewerUserIdOrderByDecidedAtDesc(actorId);
        Map<UUID, String> names = reviewerNames(
                reviews.stream().map(DocumentReview::getReviewerUserId).toList());
        return reviews.stream()
                .map(r -> DocumentReviewResponse.from(r, names.get(r.getReviewerUserId())))
                .toList();
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

        // A Manager who delegated a tier steps aside for it: the API
        // refuses their verdict on that tier (see
        // {@link #requireNotDelegatedAway}), so keeping the work in
        // their queue would offer an action that always fails.
        List<String> delegatedTiers = delegatedTiersOf(actorId, workspaceId);

        if (caps.canStaffReview() && !delegatedTiers.contains(TIER_STAFF)) {
            staffQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.PENDING,
                    "STAFF_REVIEW", filter, actorId));
        }
        if (caps.canManagerApprove() && !delegatedTiers.contains(TIER_MANAGER)) {
            managerQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.IN_REVIEW,
                    "MANAGER_APPROVAL", filter, actorId));
            publishQueue.addAll(itemsForWorkspaceStatus(workspaceId, VersionStatus.APPROVED,
                    "PUBLISH", filter, actorId));
        }

        return new ReviewQueueResponse(
                workspaceId,
                caps.platformRoles(),
                caps.member(),
                caps.workspaceManager(),
                caps.documentationOwner(),
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
        // of one query per version. Only the current verdicts count —
        // a superseded verdict is history, not the latest opinion.
        List<UUID> versionIds = versions.stream().map(DocumentVersion::getId).toList();
        Map<UUID, DocumentReview> latestReview = new HashMap<>();
        for (DocumentReview review : reviewRepository
                .findAllByDocumentVersionIdInAndSupersededAtIsNullOrderByDecidedAtDesc(versionIds)) {
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
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.PENDING, "STAFF_REVIEW",
                filter, actorId);
    }

    /**
     * All items waiting on final approval in the workspace. Requires
     * Manager-tier capability.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemResponse> managerQueue(UUID actorId, UUID workspaceId,
                                                   ReviewQueueFilter filter) {
        authorizationService.requireDocumentApprover(actorId, workspaceId);
        return itemsForWorkspaceStatus(workspaceId, VersionStatus.IN_REVIEW, "MANAGER_APPROVAL",
                filter, actorId);
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
        return itemsForWorkspaceStatus(workspaceId, status, action, filter, null);
    }

    /**
     * The same queue, with the caller's own submissions left out.
     *
     * <p>A queue is a list of things the viewer can <em>act</em> on. A
     * version the viewer submitted themselves is not one of them: the
     * self-review guard refuses the verdict, so offering the row would
     * offer an action that always fails. That is exactly what the
     * reviewer saw — their own document sitting in the queue, and an
     * error the moment they touched it.
     *
     * <p>The own submissions are not lost: they are listed under "My
     * submissions", which is where the author watches their document
     * move through the workflow.
     *
     * <p>The exception is a workspace with nobody else who could review
     * at this tier. Then the author is the only possible reviewer, and
     * hiding the row would leave the document with no way forward at
     * all (see {@link #requireNotSelfReview}).
     */
    private List<ReviewQueueItemResponse> itemsForWorkspaceStatus(UUID workspaceId,
                                                                   VersionStatus status,
                                                                   String action,
                                                                   ReviewQueueFilter filter,
                                                                   UUID actorId) {
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

        // The tier this queue belongs to, so the "can anybody else act
        // on this?" question can be asked against the right set of
        // reviewers.
        String tier = switch (action) {
            case "STAFF_REVIEW" -> TIER_STAFF;
            case "MANAGER_APPROVAL" -> TIER_MANAGER;
            default -> TIER_STAFF;
        };
        // Whether somebody other than the viewer could act on this tier.
        // When nobody could, the viewer's own submissions stay in the
        // queue — they are the only possible reviewer.
        boolean someoneElseCanAct = actorId == null
                || !isSoleReviewer(workspaceId, tier, actorId);
        // The publish queue is a different kind of queue: publishing is
        // a mechanical act on a version that already carries an approval,
        // not a verdict about the content. The viewer's own approved
        // version stays in it — hiding it would strand the document
        // between "approved" and "published".
        boolean filterOwnSubmissions = !"PUBLISH".equals(action);

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
            // The viewer's own submission is not something they can act
            // on, so it does not belong in a queue of actionable work.
            // It stays visible under "My submissions".
            if (filterOwnSubmissions && someoneElseCanAct && actorId != null
                    && actorId.equals(v.getSubmittedByUserId())) {
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
     *
     * <p>Only verdicts that are still <em>current</em> count. A verdict
     * that a later Staff verdict replaced is kept in the trail but no
     * longer represents the opinion of the tier — otherwise a Manager
     * could be shown a Staff approval that the Staff tier itself had
     * already withdrawn.
     */
    private DocumentReview latestStaffReview(UUID versionId) {
        List<DocumentReview> staffReviews = reviewRepository
                .findAllByDocumentVersionIdAndReviewerRoleAndSupersededAtIsNullOrderByDecidedAtDesc(
                        versionId, TIER_STAFF);
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

        Integer submittedVersionNumber = version.getVersionNumber() != null
                ? version.getVersionNumber() : 0;
        auditLogService.record(actorId, "USER", "document.submitted_for_review",
                "document_version", versionId.toString(), document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId.toString(),
                        "versionNumber", submittedVersionNumber));

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
     *
     * <p>Delegations are honoured here: when a Manager delegated the
     * Staff tier of the workspace, the delegate is notified and the
     * delegating Manager is not — handing work over means somebody else
     * now does it.
     */
    private void notifyReviewers(Document document, DocumentVersion version, UUID actorId) {
        try {
            List<UUID> recipients = staffReviewersFor(document.getWorkspaceId());
            recipients = applyDelegation(document.getWorkspaceId(), TIER_STAFF, recipients);
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
     *
     * <p>Delegations are honoured here as well: the delegate of the
     * Manager tier receives the work, the delegating Manager does not.
     */
    private void notifyManagerTier(Document document, DocumentVersion version, UUID actorId) {
        try {
            List<UUID> recipients = managerApproversFor(document.getWorkspaceId());
            recipients = applyDelegation(document.getWorkspaceId(), TIER_MANAGER, recipients);
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
     * Route a tier's notifications through the delegations of the
     * workspace.
     *
     * <p>The delegate is added to the recipients — a delegation nobody
     * is told about would be pointless — and the delegating Manager is
     * removed from them, because the whole point of handing work over
     * is that somebody else does it now.
     *
     * <p>The delegate is added even when they do not hold the tier
     * permission themselves: the delegation service refuses to create
     * such a delegation in the first place, so an existing delegation
     * always implies the permission.
     */
    private List<UUID> applyDelegation(UUID workspaceId, String tier, List<UUID> recipients) {
        List<UUID> delegates = delegationService.delegatesFor(workspaceId, tier);
        if (delegates.isEmpty()) {
            return recipients;
        }
        List<UUID> delegators = delegationService.delegatorsFor(workspaceId, tier);
        java.util.LinkedHashSet<UUID> out = new java.util.LinkedHashSet<>(recipients);
        out.addAll(delegates);
        out.removeAll(delegators);
        return List.copyOf(out);
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
