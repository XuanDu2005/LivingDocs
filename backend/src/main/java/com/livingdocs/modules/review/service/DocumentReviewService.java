package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.review.dto.DocumentReviewResponse;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.model.DocumentReview;
import com.livingdocs.modules.review.model.ReviewDecision;
import com.livingdocs.modules.review.repository.DocumentReviewRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
 */
@Service
public class DocumentReviewService {

    private static final Logger log = LoggerFactory.getLogger(DocumentReviewService.class);

    private final DocumentReviewRepository reviewRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;

    public DocumentReviewService(DocumentReviewRepository reviewRepository,
                                 DocumentVersionRepository versionRepository,
                                 DocumentRepository documentRepository,
                                 WorkspaceService workspaceService,
                                 AuditLogService auditLogService) {
        this.reviewRepository = reviewRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
    }

    /**
     * Staff submits a review verdict on a pending version.
     */
    @Transactional
    public DocumentReviewResponse submitStaffReview(UUID actorId, UUID documentId,
                                                     UUID versionId, SubmitReviewRequest req) {
        Document document = loadAndAuthorize(actorId, documentId);
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        DocumentVersion version = loadVersion(documentId, versionId);
        if (version.getStatus() != VersionStatus.PENDING && version.getStatus() != VersionStatus.IN_REVIEW) {
            throw new BadRequestException("Only pending or in-review versions can be reviewed");
        }
        DocumentReview review = new DocumentReview(
                version.getId(), actorId, "STAFF", req.decision(), req.comment()
        );
        DocumentReview saved = reviewRepository.save(review);

        applyStaffVerdict(document, version, req.decision());
        auditLogService.record(actorId, "STAFF",
                "document.review", "document_version", saved.getId().toString(),
                document.getWorkspaceId(),
                java.util.Map.of("documentId", documentId.toString(),
                        "versionId", versionId.toString(),
                        "decision", req.decision().name()));
        log.info("Staff review submitted: document={} version={} decision={}",
                documentId, version.getVersionNumber(), req.decision());
        return DocumentReviewResponse.from(saved);
    }

    /**
     * Manager grants (or denies) final approval on a version.
     */
    @Transactional
    public DocumentReviewResponse submitManagerDecision(UUID actorId, UUID documentId,
                                                       UUID versionId, SubmitReviewRequest req) {
        Document document = loadAndAuthorize(actorId, documentId);
        workspaceService.requireRole(actorId, document.getWorkspaceId(), WorkspaceRole.MANAGER);
        DocumentVersion version = loadVersion(documentId, versionId);

        if (req.decision() == ReviewDecision.APPROVED) {
            // Staff must have approved first.
            boolean staffApproved = reviewRepository
                    .findAllByDocumentVersionIdAndReviewerRoleOrderByDecidedAtDesc(version.getId(), "STAFF")
                    .stream().anyMatch(r -> r.getDecision() == ReviewDecision.APPROVED);
            if (!staffApproved) {
                throw new BadRequestException("Manager cannot approve before Staff approves");
            }
        }

        DocumentReview review = new DocumentReview(
                version.getId(), actorId, "MANAGER", req.decision(), req.comment()
        );
        DocumentReview saved = reviewRepository.save(review);

        applyManagerVerdict(document, version, req.decision());
        auditLogService.record(actorId, "MANAGER",
                "document.approval", "document_version", saved.getId().toString(),
                document.getWorkspaceId(),
                java.util.Map.of("documentId", documentId.toString(),
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
    // helpers
    // -----------------------------------------------------------------

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