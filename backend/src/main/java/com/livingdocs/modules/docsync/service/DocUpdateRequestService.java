package com.livingdocs.modules.docsync.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.document.service.DocumentFileResolver;
import com.livingdocs.modules.docsync.dto.DocUpdateRequestDto;
import com.livingdocs.modules.docsync.model.DocUpdateRequest;
import com.livingdocs.modules.docsync.model.DocUpdateStatus;
import com.livingdocs.modules.docsync.repository.DocUpdateRequestRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubRepositoryService;
import com.livingdocs.modules.review.dto.SubmitReviewRequest;
import com.livingdocs.modules.review.model.ReviewDecision;
import com.livingdocs.modules.review.service.DocumentReviewService;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Manages the lifecycle of {@link DocUpdateRequest} proposals.
 *
 * <h2>How this relates to the document review workflow</h2>
 *
 * <p>A proposal used to be a completely separate workflow from the
 * document review: approving it only flipped a status column, so the
 * proposed content never became a {@link DocumentVersion}, never
 * appeared in the shared review queue, and never left an audit trail.
 *
 * <p>Now a proposal is <em>materialised</em>. When it is created and a
 * matching document exists, the proposed body is written as a new version
 * of that document which is submitted for review straight away. The
 * proposal then carries a reference to that version, and every decision
 * on the proposal is delegated to {@link DocumentReviewService} — the
 * same two-tier Staff → Manager workflow every other document goes
 * through.
 *
 * <p>The consequence is that a proposal and a document version are two
 * views of the same piece of work, not two competing queues.
 *
 * <p>Three actors interact with the queue:
 *
 * <ol>
 *   <li><b>Scanner</b> — calls {@link #createProposal} when a push
 *       event triggers drift between a doc and the underlying
 *       code. The request is created in {@code PENDING_REVIEW}.</li>
 *   <li><b>Reviewer (Staff)</b> — calls {@link #approve} or
 *       {@link #reject}, which are forwarded to the Staff tier of the
 *       document review workflow.</li>
 *   <li><b>Approver (Manager)</b> — calls {@link #apply} on an
 *       already-approved request to open a real GitHub PR. This is the
 *       "push to Git" step the workflow is built around.</li>
 * </ol>
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>Any workspace member with the {@code STAFF} or {@code MANAGER}
 *       role can list and view proposals.</li>
 *   <li>Approving or rejecting requires the {@code DOCUMENT_VERSION_REVIEW}
 *       permission — the same capability the Staff tier of the document
 *       review requires. The two-tier structure, the self-review guard
 *       and the audit trail all apply.</li>
 *   <li>Applying (creating the PR) requires the {@code MANAGER} role
 *       <em>and</em> the {@code SCM_COMMIT} permission. This is the
 *       trust boundary: only managers can touch Git.</li>
 * </ul>
 */
@Service
public class DocUpdateRequestService {

    private static final Logger log = LoggerFactory.getLogger(DocUpdateRequestService.class);

    /**
     * Statuses that count as "a decision is still pending". A file in
     * one of these states already has a row in the review queue, so
     * auto-queueing it again would just create a duplicate.
     */
    private static final List<DocUpdateStatus> OPEN_STATUSES = List.of(
            DocUpdateStatus.PENDING_REVIEW,
            DocUpdateStatus.APPROVED);

    private final DocUpdateRequestRepository repository;
    private final RepositoryRepository repositoryRepository;
    private final GithubClient githubClient;
    private final WorkspaceService workspaceService;
    private final GithubRepositoryService repositoryService;
    private final PermissionService permissionService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final DocumentFileResolver documentFileResolver;
    private final DocumentVersionService versionService;
    private final DocumentVersionRepository versionRepository;
    private final DocumentReviewService reviewService;

    public DocUpdateRequestService(DocUpdateRequestRepository repository,
                                   RepositoryRepository repositoryRepository,
                                   GithubClient githubClient,
                                   WorkspaceService workspaceService,
                                   GithubRepositoryService repositoryService,
                                   PermissionService permissionService,
                                   AuthorizationService authorizationService,
                                   DocumentRepository documentRepository,
                                   DocumentFileResolver documentFileResolver,
                                   DocumentVersionService versionService,
                                   DocumentVersionRepository versionRepository,
                                   DocumentReviewService reviewService) {
        this.repository = repository;
        this.repositoryRepository = repositoryRepository;
        this.githubClient = githubClient;
        this.workspaceService = workspaceService;
        this.repositoryService = repositoryService;
        this.permissionService = permissionService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.documentFileResolver = documentFileResolver;
        this.versionService = versionService;
        this.versionRepository = versionRepository;
        this.reviewService = reviewService;
    }

    // ── Listing ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocUpdateRequestDto> listForRepository(UUID workspaceId, UUID repositoryId,
                                                       UUID actorUserId, DocUpdateStatus status) {
        workspaceService.requireMember(actorUserId, workspaceId);
        List<DocUpdateRequest> rows = (status != null)
                ? repository.findByRepositoryIdAndStatusOrderByRequestedAtDesc(repositoryId, status)
                : repository.findByRepositoryIdOrderByRequestedAtDesc(repositoryId);
        return rows.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public DocUpdateRequestDto get(UUID workspaceId, UUID requestId, UUID actorUserId) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        return toDto(r);
    }

    /**
     * The projection of a proposal, with the status of the version it
     * materialised into.
     *
     * <p>The version status is what tells the reviewer where the
     * proposal stands in the two-tier workflow: the proposal's own
     * status only records the Staff decision, while the version carries
     * the Manager decision that the push to Git depends on.
     */
    private DocUpdateRequestDto toDto(DocUpdateRequest r) {
        VersionStatus versionStatus = null;
        if (r.getDocumentVersionId() != null) {
            versionStatus = versionRepository.findById(r.getDocumentVersionId())
                    .map(DocumentVersion::getStatus)
                    .orElse(null);
        }
        return DocUpdateRequestDto.from(r, versionStatus);
    }

    // ── Create (called by webhook or scan) ────────────────────────────────

    /**
     * Queue a proposal for review. Created in {@link DocUpdateStatus#PENDING_REVIEW}.
     *
     * <p>Returns {@code null} if {@code proposedContent} equals
     * {@code currentContent} (no point in queueing a no-op).
     */
    @Transactional
    public DocUpdateRequestDto createProposal(UUID workspaceId, UUID repositoryId, UUID documentId,
                                              String filePath, String currentContent,
                                              String proposedContent, String diffSummary,
                                              String triggerCommitSha, String triggerBranch,
                                              UUID requestedByUserId) {
        if (proposedContent == null || proposedContent.equals(currentContent)) {
            log.debug("Skipping DocUpdateRequest for {} — proposed content identical to current", filePath);
            return null;
        }
        DocUpdateRequest req = new DocUpdateRequest(
                workspaceId, repositoryId, documentId, filePath,
                currentContent, proposedContent, diffSummary,
                triggerCommitSha, triggerBranch, requestedByUserId);
        DocUpdateRequest saved = repository.save(req);
        log.info("Created DocUpdateRequest {} for {}/{} (status=PENDING_REVIEW)",
                saved.getId(), repositoryId, filePath);
        return DocUpdateRequestDto.from(saved);
    }

    /**
     * Queue a proposal for review, but only when the file does not
     * already have one waiting for a decision.
     *
     * <p>Scanning a repository repeatedly would otherwise pile up a
     * duplicate PENDING_REVIEW row for every drifted file. A file that
     * was already rejected or applied is allowed through again — the
     * drift may have come back with a new commit.
     *
     * @return the newly created request, or {@code null} when nothing was
     *         queued (no real change, or a proposal is already pending)
     */
    @Transactional
    public DocUpdateRequestDto createProposalIfNotPending(UUID workspaceId, UUID repositoryId,
                                                          UUID documentId, String filePath,
                                                          String currentContent, String proposedContent,
                                                          String diffSummary, String triggerCommitSha,
                                                          String triggerBranch, UUID requestedByUserId) {
        boolean alreadyPending = repository
                .findFirstByRepositoryIdAndFilePathAndStatusIn(
                        repositoryId, filePath, OPEN_STATUSES)
                .isPresent();
        if (alreadyPending) {
            log.debug("Skipping DocUpdateRequest for {} — a proposal is already pending", filePath);
            return null;
        }
        DocUpdateRequestDto created = createProposal(workspaceId, repositoryId, documentId, filePath,
                currentContent, proposedContent, diffSummary,
                triggerCommitSha, triggerBranch, requestedByUserId);
        if (created == null) {
            return null;
        }
        // Materialise the proposal into a version of the document, so it
        // joins the shared review workflow instead of living in a queue
        // of its own.
        materialize(workspaceId, repositoryId, filePath, proposedContent, diffSummary,
                triggerCommitSha, requestedByUserId, created.id());
        return repository.findById(created.id()).map(this::toDto).orElse(created);
    }

    /**
     * Write the proposed content as a new version of the document that
     * corresponds to the file, and submit it for review.
     *
     * <p>The document is resolved through {@link DocumentFileResolver}: a
     * file in the repository maps to exactly one document, and that
     * document is the one whose content the proposal is about.
     *
     * <p>When no document matches the file, the proposal stays a
     * repository-level proposal: there is nothing to attach a version to.
     * This is not an error — a repository may well contain markdown files
     * that LivingDocs does not manage.
     */
    private void materialize(UUID workspaceId, UUID repositoryId, String filePath,
                             String proposedContent, String diffSummary,
                             String triggerCommitSha, UUID requestedByUserId,
                             UUID requestId) {
        Document document = documentFileResolver.resolve(repositoryId, filePath).orElse(null);
        if (document == null) {
            log.debug("No document publishes {} in repository {} — the proposal stays "
                    + "repository-level", filePath, repositoryId);
            return;
        }
        try {
            String summary = diffSummary == null || diffSummary.isBlank()
                    ? "Auto-generated update for " + filePath
                    : "Auto-generated update for " + filePath;
            DocumentVersion version = versionService.createSubmittedAiVersion(
                    requestedByUserId, document.getId(), proposedContent, summary, requestedByUserId);
            DocUpdateRequest req = repository.findById(requestId).orElse(null);
            if (req != null) {
                req.markMaterialized(version.getId(), document.getId());
                repository.save(req);
            }
            log.info("DocUpdateRequest {} materialised as version {} of document {}",
                    requestId, version.getVersionNumber(), document.getId());
        } catch (Exception e) {
            // A failure to materialise must not lose the proposal: the
            // reviewer can still approve it and push it as a PR.
            log.warn("Could not materialise DocUpdateRequest {} as a document version: {}",
                    requestId, e.getMessage());
        }
    }

    // ── Approve / Reject (reviewer actions) ─────────────────────────────

    /**
     * Approve the proposal.
     *
     * <p>The decision is recorded on the materialised version through the
     * Staff tier of the document review workflow, so the two-tier
     * structure, the self-review guard and the audit trail all apply.
     */
    @Transactional
    public DocUpdateRequestDto approve(UUID workspaceId, UUID requestId, UUID actorUserId, String comment) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        if (r.getStatus() != DocUpdateStatus.PENDING_REVIEW) {
            throw new BadRequestException(
                    "Request is " + r.getStatus() + ", only PENDING_REVIEW can be approved");
        }
        // The same capability the Staff tier of the document review
        // requires — and resolved the same way, so an administrator is
        // not locked out of their own workspace. Before this, any
        // workspace member could approve a proposal, which is a much
        // weaker gate than the rest of the platform.
        authorizationService.requireDocumentReviewer(actorUserId, workspaceId);

        if (r.getDocumentVersionId() != null && r.getDocumentId() != null) {
            reviewService.submitStaffReview(actorUserId, r.getDocumentId(), r.getDocumentVersionId(),
                    new SubmitReviewRequest(ReviewDecision.APPROVED, comment));
        }
        r.approve(actorUserId, comment);
        DocUpdateRequest saved = repository.save(r);
        log.info("DocUpdateRequest {} APPROVED by user {}", requestId, actorUserId);
        return toDto(saved);
    }

    @Transactional
    public DocUpdateRequestDto reject(UUID workspaceId, UUID requestId, UUID actorUserId, String comment) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        if (r.getStatus() != DocUpdateStatus.PENDING_REVIEW) {
            throw new BadRequestException(
                    "Request is " + r.getStatus() + ", only PENDING_REVIEW can be rejected");
        }
        authorizationService.requireDocumentReviewer(actorUserId, workspaceId);

        if (r.getDocumentVersionId() != null && r.getDocumentId() != null) {
            reviewService.submitStaffReview(actorUserId, r.getDocumentId(), r.getDocumentVersionId(),
                    new SubmitReviewRequest(ReviewDecision.REQUEST_CHANGES, comment));
        }
        r.reject(actorUserId, comment);
        DocUpdateRequest saved = repository.save(r);
        log.info("DocUpdateRequest {} REJECTED by user {}", requestId, actorUserId);
        return toDto(saved);
    }

    // ── Apply (manager pushes to Git) ───────────────────────────────────

    @Transactional
    public DocUpdateRequestDto apply(UUID workspaceId, UUID requestId, UUID actorUserId) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        // Only managers can push to Git.
        workspaceService.requireRole(actorUserId, workspaceId, WorkspaceRole.MANAGER);
        // ...and only a holder of SCM_COMMIT may write to an external
        // repository. Both guards are required: the workspace role says
        // "you own this workspace", the permission says "you may
        // publish".
        permissionService.requireAnyPermission(actorUserId, PermissionCodes.SCM_COMMIT);

        if (r.getStatus() != DocUpdateStatus.APPROVED) {
            throw new BadRequestException(
                    "Request is " + r.getStatus() + ", only APPROVED can be applied");
        }

        // The Manager tier has to have spoken too.
        //
        // The proposal's own APPROVED status only records that the Staff
        // tier signed off. Pushing to Git on that basis alone would let
        // a single review push unreviewed content to a repository — the
        // same hole the document commit path closed with
        // DocumentApprovalGateService. When the proposal was materialised
        // into a version, the version carries the two-tier state, so the
        // Manager decision is required before anything is written.
        if (r.getDocumentVersionId() != null && r.getDocumentId() != null) {
            DocumentVersion version = versionRepository
                    .findById(r.getDocumentVersionId())
                    .orElse(null);
            if (version == null) {
                throw new NotFoundException("The version of this proposal no longer exists");
            }
            if (version.getStatus() != VersionStatus.APPROVED
                    && version.getStatus() != VersionStatus.PUBLISHED) {
                throw new BadRequestException(
                        "This proposal still needs a Manager approval before it can be pushed to Git "
                                + "(the version is " + version.getStatus() + ").");
            }
        }

        Repository repo = repositoryRepository.findById(r.getRepositoryId())
                .orElseThrow(() -> new NotFoundException("Repository not found: " + r.getRepositoryId()));

        String accessToken = repositoryService.getAccessTokenForWorkspace(workspaceId);
        String baseBranch = (r.getTriggerBranch() != null && !r.getTriggerBranch().isBlank())
                ? r.getTriggerBranch() : repo.getDefaultBranch();
        String headBranch = "docs/sync-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("Applying DocUpdateRequest {} -> PR branch {}/{}:{}",
                requestId, repo.getFullName(), headBranch, r.getFilePath());

        // Clean up any stale branch from a previous attempt.
        if (githubClient.branchExists(accessToken, repo.getOwner(), repo.getName(), headBranch)) {
            log.info("Deleting stale branch {} before re-applying", headBranch);
            githubClient.deleteBranch(accessToken, repo.getOwner(), repo.getName(), headBranch);
        }

        String prTitle = "📝 Sync documentation: " + r.getFilePath();
        String prBody = (r.getDiffSummary() == null ? "" : r.getDiffSummary() + "\n\n") +
                "Triggered by commit " + (r.getTriggerCommitSha() == null ? "manual" : r.getTriggerCommitSha()) + ".";
        String prUrl = githubClient.createFileAsPullRequest(
                accessToken, repo.getOwner(), repo.getName(),
                baseBranch, headBranch, r.getFilePath(), r.getProposedContent(),
                "docs: sync with latest code - " + r.getFilePath(),
                prTitle, prBody);

        r.markApplied(prUrl);
        DocUpdateRequest saved = repository.save(r);
        log.info("DocUpdateRequest {} applied — PR {}", requestId, prUrl);
        return toDto(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DocUpdateRequest loadAndAuthorize(UUID workspaceId, UUID requestId, UUID actorUserId) {
        DocUpdateRequest r = repository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("DocUpdateRequest not found: " + requestId));
        if (!r.getWorkspaceId().equals(workspaceId)) {
            throw new ForbiddenException("Request belongs to a different workspace");
        }
        workspaceService.requireMember(actorUserId, workspaceId);
        return r;
    }
}
