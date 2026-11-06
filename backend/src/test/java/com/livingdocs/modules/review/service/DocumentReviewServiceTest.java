package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.notification.service.NotificationService;
import com.livingdocs.modules.review.dto.ReviseVersionRequest;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;
import com.livingdocs.modules.review.repository.DocumentReviewRepository;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two rules that make the two-tier review workflow worth having:
 * a reviewer may not review their own submission, and a Manager may
 * not approve before the Staff tier did.
 */
class DocumentReviewServiceTest {

    private DocumentReviewRepository reviewRepository;
    private DocumentVersionRepository versionRepository;
    private DocumentRepository documentRepository;
    private AuthorizationService authorizationService;
    private PermissionService permissionService;
    private WorkspaceMemberRepository memberRepository;
    private ReviewDelegationService delegationService;
    private DocumentReviewService service;

    private UUID actorId;
    private UUID otherId;
    private UUID documentId;
    private UUID versionId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(DocumentReviewRepository.class);
        versionRepository = mock(DocumentVersionRepository.class);
        documentRepository = mock(DocumentRepository.class);
        authorizationService = mock(AuthorizationService.class);
        permissionService = mock(PermissionService.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        delegationService = mock(ReviewDelegationService.class);

        service = new DocumentReviewService(
                reviewRepository,
                versionRepository,
                documentRepository,
                mock(WorkspaceService.class),
                mock(AuditLogService.class),
                authorizationService,
                permissionService,
                mock(NotificationService.class),
                memberRepository,
                mock(UserRepository.class),
                mock(DriftAlertRepository.class),
                mock(DocTemplateRepository.class),
                mock(DocTemplateService.class),
                mock(TemplateComplianceService.class),
                delegationService);

        actorId = UUID.randomUUID();
        otherId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        // The repository hands back whatever it was given. A new row has
        // no id yet — the persistence layer assigns it — so the service
        // must not assume one is there.
        when(versionRepository.save(any(DocumentVersion.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // No delegation by default: the reassignment guard must not fire
        // unless a delegation actually exists.
        when(delegationService.delegatorsFor(any(), anyString())).thenReturn(java.util.List.of());
        when(delegationService.delegatesFor(any(), anyString())).thenReturn(java.util.List.of());
        // By default the workspace has another reviewer who holds the
        // tier permissions. That is the interesting case for the
        // self-review guard: somebody else could have done the work, so
        // the author must not do it themselves.
        givenAnotherReviewer();
    }

    /**
     * Put somebody else in the workspace who may review and approve.
     */
    private void givenAnotherReviewer() {
        WorkspaceMember member = mock(WorkspaceMember.class);
        when(member.getUserId()).thenReturn(otherId);
        when(memberRepository.findAllByWorkspaceId(workspaceId))
                .thenReturn(java.util.List.of(member));
        when(permissionService.hasAnyPermission(any(), anyString())).thenReturn(true);
    }

    /**
     * Leave the workspace with nobody but the actor who may review —
     * the single-reviewer case, where the self-review guard yields.
     */
    private void givenSoleReviewer() {
        WorkspaceMember member = mock(WorkspaceMember.class);
        when(member.getUserId()).thenReturn(actorId);
        when(memberRepository.findAllByWorkspaceId(workspaceId))
                .thenReturn(java.util.List.of(member));
        when(permissionService.hasAnyPermission(any(), anyString())).thenReturn(true);
    }

    private Document givenDocument() {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getWorkspaceId()).thenReturn(workspaceId);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        return document;
    }

    private DocumentVersion givenVersion(VersionStatus status, UUID submitter) {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(versionId);
        when(version.getDocumentId()).thenReturn(documentId);
        when(version.getStatus()).thenReturn(status);
        when(version.getSubmittedByUserId()).thenReturn(submitter);
        when(version.getVersionNumber()).thenReturn(3);
        when(version.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(versionRepository.findByIdForUpdate(versionId)).thenReturn(Optional.of(version));
        return version;
    }

    /**
     * The core rule of the whole feature: a reviewer may not act on a
     * version they submitted themselves.
     */
    @Test
    void aReviewerMayNotReviewTheirOwnSubmission() {
        givenDocument();
        givenVersion(VersionStatus.PENDING, actorId);

        assertThrows(ForbiddenException.class, () -> service.submitStaffReview(
                actorId, documentId, versionId,
                new SubmitReviewRequest(ReviewDecision.APPROVED, "looks good", null, null, null)));

        verify(reviewRepository, never()).save(any());
    }

    /**
     * The guard must not be bypassable by leaving a comment first: the
     * decision is irrelevant, the actor is the submitter.
     */
    @Test
    void theSelfReviewGuardAppliesToEveryDecision() {
        givenDocument();
        givenVersion(VersionStatus.PENDING, actorId);

        assertThrows(ForbiddenException.class, () -> service.submitStaffReview(
                actorId, documentId, versionId,
                new SubmitReviewRequest(ReviewDecision.COMMENT, "just a note", null, null, null)));
    }

    /**
     * A reviewer acting on somebody else's version is fine — that is the
     * whole point of the tier.
     */
    @Test
    void aReviewerMayReviewSomebodyElsesSubmission() {
        givenDocument();
        givenVersion(VersionStatus.PENDING, otherId);

        // The service will fail later on the audit/notification wiring,
        // but the self-review guard must not be what stops it.
        try {
            service.submitStaffReview(
                    actorId, documentId, versionId,
                    new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null));
        } catch (ForbiddenException e) {
            throw new AssertionError("the self-review guard must not fire for another author's version", e);
        } catch (RuntimeException ignored) {
            // Any other failure is out of scope for this test.
        }
    }

    /**
     * A version that is not awaiting review cannot be reviewed at all.
     */
    @Test
    void onlyPendingVersionsCanBeReviewed() {
        givenDocument();
        givenVersion(VersionStatus.DRAFT, otherId);

        assertThrows(com.livingdocs.common.exception.BadRequestException.class,
                () -> service.submitStaffReview(
                        actorId, documentId, versionId,
                        new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null)));
    }

    /**
     * A revision is only possible while the version is still waiting —
     * once it is approved, the content is history.
     */
    @Test
    void onlyPendingVersionsCanBeRevised() {
        givenDocument();
        givenVersion(VersionStatus.APPROVED, otherId);

        assertThrows(com.livingdocs.common.exception.BadRequestException.class,
                () -> service.revise(
                        actorId, documentId, versionId,
                        new ReviseVersionRequest("# Fixed\n\ntext", "typo")));
    }

    /**
     * The revision keeps the original submitter: the author still finds
     * the version under "My submissions", and the reviewer is not
     * recorded as the person who submitted it.
     */
    @Test
    void aRevisionKeepsTheOriginalSubmitter() {
        givenDocument();
        DocumentVersion version = givenVersion(VersionStatus.PENDING, otherId);
        when(version.getSourceCommitSha()).thenReturn("abc1234");
        when(version.getSourcePrId()).thenReturn(null);
        when(version.getTemplateId()).thenReturn(null);
        when(version.getConfidenceScore()).thenReturn(0.9f);
        when(version.getGroundingJson()).thenReturn("{}");
        when(version.getSourceTicketRef()).thenReturn("PROJ-1");
        when(versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(Optional.of(version));

        var revised = service.revise(
                actorId, documentId, versionId,
                new ReviseVersionRequest("# Fixed\n\ntext", "typo"));

        assertEquals(otherId, revised.getSubmittedByUserId(),
                "the revision is still the original submission");
        assertEquals(VersionStatus.PENDING, revised.getStatus(),
                "the revision still has to be reviewed");
        assertEquals("PROJ-1", revised.getSourceTicketRef(),
                "the revision keeps the grounding of the version it replaces");
    }

    /**
     * The version that was reviewed becomes history rather than being
     * overwritten.
     */
    @Test
    void aRevisionSupersedesTheVersionItReplaces() {
        givenDocument();
        DocumentVersion version = givenVersion(VersionStatus.PENDING, otherId);
        when(version.getSourceCommitSha()).thenReturn(null);
        when(version.getSourcePrId()).thenReturn(null);
        when(version.getTemplateId()).thenReturn(null);
        when(version.getConfidenceScore()).thenReturn(null);
        when(version.getGroundingJson()).thenReturn(null);
        when(version.getSourceTicketRef()).thenReturn(null);
        when(versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(Optional.of(version));

        service.revise(
                actorId, documentId, versionId,
                new ReviseVersionRequest("# Fixed\n\ntext", null));

        verify(version).setStatus(VersionStatus.SUPERSEDED);
    }

    // -----------------------------------------------------------------
    // delegation (requirement #12 of the MANAGER role)
    // -----------------------------------------------------------------

    /**
     * A Manager who delegated the Staff tier does not review it any
     * more. Handing work over has to mean something: if the delegator
     * could still act, the delegation would only redirect the
     * notification while the work stayed exactly where it was.
     */
    @Test
    void aDelegatingManagerMayNotReviewTheDelegatedTier() {
        givenDocument();
        givenVersion(VersionStatus.PENDING, otherId);
        when(delegationService.delegatorsFor(workspaceId, "STAFF"))
                .thenReturn(java.util.List.of(actorId));

        assertThrows(ForbiddenException.class, () -> service.submitStaffReview(
                actorId, documentId, versionId,
                new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null)));

        verify(reviewRepository, never()).save(any());
    }

    /**
     * The same rule on the approval tier.
     */
    @Test
    void aDelegatingManagerMayNotApproveTheDelegatedTier() {
        givenDocument();
        givenVersion(VersionStatus.IN_REVIEW, otherId);
        when(delegationService.delegatorsFor(workspaceId, "MANAGER"))
                .thenReturn(java.util.List.of(actorId));

        assertThrows(ForbiddenException.class, () -> service.submitManagerDecision(
                actorId, documentId, versionId,
                new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null)));

        verify(reviewRepository, never()).save(any());
    }

    /**
     * The delegation of one tier does not lock the other tier: a
     * Manager who delegated the approval work may still review a
     * version. The delegation routes one act, it does not strip the
     * person of their role.
     */
    @Test
    void aDelegationOfOneTierDoesNotBlockTheOther() {
        givenDocument();
        givenVersion(VersionStatus.PENDING, otherId);
        when(delegationService.delegatorsFor(workspaceId, "MANAGER"))
                .thenReturn(java.util.List.of(actorId));

        try {
            service.submitStaffReview(
                    actorId, documentId, versionId,
                    new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null));
        } catch (ForbiddenException e) {
            throw new AssertionError(
                    "a MANAGER-tier delegation must not block a STAFF-tier review", e);
        } catch (RuntimeException ignored) {
            // Any other failure is out of scope for this test.
        }
    }

    // -----------------------------------------------------------------
    // correcting a verdict
    // -----------------------------------------------------------------

    /**
     * A reviewer who mis-clicked "Reject" has to be able to correct it.
     *
     * <p>The rejection moved the version out of the reviewable state,
     * so without a dedicated rule the correction would be refused —
     * and the version would be stuck on a verdict nobody meant.
     */
    @Test
    void aReviewerMayCorrectTheirOwnRejection() {
        givenDocument();
        // The version is REJECTED — that is what the mis-click did.
        givenVersion(VersionStatus.REJECTED, otherId);
        // The current Staff verdict is this reviewer's own rejection.
        DocumentReview own = mock(DocumentReview.class);
        when(own.getDecision()).thenReturn(ReviewDecision.REJECTED);
        when(own.getReviewerUserId()).thenReturn(actorId);
        when(reviewRepository
                .findAllByDocumentVersionIdAndReviewerRoleAndSupersededAtIsNullOrderByDecidedAtDesc(
                        versionId, "STAFF"))
                .thenReturn(java.util.List.of(own));

        try {
            service.submitStaffReview(
                    actorId, documentId, versionId,
                    new SubmitReviewRequest(ReviewDecision.APPROVED, "correction", null, null, null));
        } catch (ForbiddenException e) {
            throw new AssertionError("a reviewer must be able to correct their own verdict", e);
        } catch (com.livingdocs.common.exception.BadRequestException e) {
            throw new AssertionError(
                    "a REJECTED version carrying the reviewer's own verdict must stay correctable", e);
        } catch (RuntimeException ignored) {
            // Any other failure is out of scope for this test.
        }
    }

    /**
     * The correction is not a free-for-all: another reviewer's
     * rejection is not this reviewer's to overwrite.
     */
    @Test
    void aReviewerMayNotCorrectSomebodyElsesRejection() {
        givenDocument();
        givenVersion(VersionStatus.REJECTED, otherId);
        DocumentReview theirs = mock(DocumentReview.class);
        when(theirs.getDecision()).thenReturn(ReviewDecision.REJECTED);
        when(theirs.getReviewerUserId()).thenReturn(otherId);
        when(reviewRepository
                .findAllByDocumentVersionIdAndReviewerRoleAndSupersededAtIsNullOrderByDecidedAtDesc(
                        versionId, "STAFF"))
                .thenReturn(java.util.List.of(theirs));

        assertThrows(com.livingdocs.common.exception.BadRequestException.class,
                () -> service.submitStaffReview(
                        actorId, documentId, versionId,
                        new SubmitReviewRequest(ReviewDecision.APPROVED, "correction", null, null, null)));
    }

    // -----------------------------------------------------------------
    // the self-review guard and its single-reviewer escape hatch
    // -----------------------------------------------------------------

    /**
     * The approval tier needs the same guard as the review tier.
     *
     * <p>Without it a Manager who submitted a document could approve it
     * themselves as soon as the Staff tier had approved it — so the
     * final sign-off, the one the two-tier workflow exists for, could be
     * given by the author.
     */
    @Test
    void aManagerMayNotApproveTheirOwnSubmission() {
        givenDocument();
        givenVersion(VersionStatus.IN_REVIEW, actorId);
        // The Staff tier approved it, so the only thing standing in the
        // way is the self-review guard.
        DocumentReview staffApproval = mock(DocumentReview.class);
        when(staffApproval.getDecision()).thenReturn(ReviewDecision.APPROVED);
        when(reviewRepository
                .findAllByDocumentVersionIdAndReviewerRoleAndSupersededAtIsNullOrderByDecidedAtDesc(
                        versionId, "STAFF"))
                .thenReturn(java.util.List.of(staffApproval));

        assertThrows(ForbiddenException.class, () -> service.submitManagerDecision(
                actorId, documentId, versionId,
                new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null)));

        verify(reviewRepository, never()).save(any());
    }

    /**
     * A workspace with only one person who may review would deadlock
     * without an escape hatch: the submission would sit in the queue
     * forever, and the author would have to publish it to get it out —
     * exactly what the review workflow exists to prevent.
     */
    @Test
    void theSoleReviewerMayReviewTheirOwnSubmission() {
        givenDocument();
        givenSoleReviewer();
        givenVersion(VersionStatus.PENDING, actorId);

        try {
            service.submitStaffReview(
                    actorId, documentId, versionId,
                    new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null));
        } catch (ForbiddenException e) {
            throw new AssertionError(
                    "the sole eligible reviewer must not be blocked by the self-review guard", e);
        } catch (RuntimeException ignored) {
            // Any other failure is out of scope for this test.
        }
    }

    /**
     * The escape hatch is narrow: somebody else who could review has to
     * be enough for the guard to bite again.
     */
    @Test
    void theSoleReviewerExceptionNeedsNoOtherCandidate() {
        givenDocument();
        // The default setup: another reviewer exists.
        givenVersion(VersionStatus.PENDING, actorId);

        assertThrows(ForbiddenException.class, () -> service.submitStaffReview(
                actorId, documentId, versionId,
                new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null)));
    }

    /**
     * A delegate counts as an eligible reviewer: the tier is not
     * single-reviewer any more, so the guard bites again. Otherwise
     * delegating the work away would silently unlock self-review.
     */
    @Test
    void aDelegateCountsAsAnotherCandidateReviewer() {
        givenDocument();
        givenSoleReviewer();
        // The actor delegated the tier to somebody else, so that person
        // is the one who can act on it now.
        when(delegationService.delegatesFor(workspaceId, "STAFF"))
                .thenReturn(java.util.List.of(otherId));
        givenVersion(VersionStatus.PENDING, actorId);

        assertThrows(ForbiddenException.class, () -> service.submitStaffReview(
                actorId, documentId, versionId,
                new SubmitReviewRequest(ReviewDecision.APPROVED, "ok", null, null, null)));
    }
}
