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
import com.livingdocs.modules.review.model.ReviewDecision;
import com.livingdocs.modules.review.repository.DocumentReviewRepository;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
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

        service = new DocumentReviewService(
                reviewRepository,
                versionRepository,
                documentRepository,
                mock(WorkspaceService.class),
                mock(AuditLogService.class),
                authorizationService,
                mock(PermissionService.class),
                mock(NotificationService.class),
                mock(WorkspaceMemberRepository.class),
                mock(UserRepository.class),
                mock(DriftAlertRepository.class),
                mock(DocTemplateRepository.class),
                mock(DocTemplateService.class),
                mock(TemplateComplianceService.class));

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
}
