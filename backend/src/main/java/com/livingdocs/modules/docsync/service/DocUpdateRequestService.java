package com.livingdocs.modules.docsync.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.docsync.dto.DocUpdateRequestDto;
import com.livingdocs.modules.docsync.model.DocUpdateRequest;
import com.livingdocs.modules.docsync.model.DocUpdateStatus;
import com.livingdocs.modules.docsync.repository.DocUpdateRequestRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubRepositoryService;
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
 * <p>Three actors interact with the queue:
 *
 * <ol>
 *   <li><b>Webhook handler</b> — calls {@link #createProposal} when a
 *       push event triggers drift between a doc and the underlying
 *       code. The request is created in {@code PENDING_REVIEW}.</li>
 *   <li><b>Reviewer (dev)</b> — calls {@link #reject} to discard a
 *       proposal, or {@link #approve} to sign off on the proposed
 *       content. Approval does not yet create a PR.</li>
 *   <li><b>Approver (manager)</b> — calls {@link #apply} on an
 *       already-approved request to open a real GitHub PR. This is the
 *       "push to Git" step the workflow is built around.</li>
 * </ol>
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>Any workspace member with the {@code STAFF} or {@code MANAGER}
 *       role can list and view proposals.</li>
 *   <li>Approving or rejecting a proposal only requires being a member
 *       of the workspace (i.e. dev can sign off too).</li>
 *   <li>Applying (creating the PR) requires the {@code MANAGER} role.
 *       This is the trust boundary: only managers can touch Git.</li>
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

    public DocUpdateRequestService(DocUpdateRequestRepository repository,
                                   RepositoryRepository repositoryRepository,
                                   GithubClient githubClient,
                                   WorkspaceService workspaceService,
                                   GithubRepositoryService repositoryService) {
        this.repository = repository;
        this.repositoryRepository = repositoryRepository;
        this.githubClient = githubClient;
        this.workspaceService = workspaceService;
        this.repositoryService = repositoryService;
    }

    // ── Listing ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocUpdateRequestDto> listForRepository(UUID workspaceId, UUID repositoryId,
                                                       UUID actorUserId, DocUpdateStatus status) {
        workspaceService.requireMember(actorUserId, workspaceId);
        List<DocUpdateRequest> rows = (status != null)
                ? repository.findByRepositoryIdAndStatusOrderByRequestedAtDesc(repositoryId, status)
                : repository.findByRepositoryIdOrderByRequestedAtDesc(repositoryId);
        return rows.stream().map(DocUpdateRequestDto::from).toList();
    }

    @Transactional(readOnly = true)
    public DocUpdateRequestDto get(UUID workspaceId, UUID requestId, UUID actorUserId) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        return DocUpdateRequestDto.from(r);
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
        return createProposal(workspaceId, repositoryId, documentId, filePath,
                currentContent, proposedContent, diffSummary,
                triggerCommitSha, triggerBranch, requestedByUserId);
    }

    // ── Approve / Reject (reviewer actions) ─────────────────────────────

    @Transactional
    public DocUpdateRequestDto approve(UUID workspaceId, UUID requestId, UUID actorUserId, String comment) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        if (r.getStatus() != DocUpdateStatus.PENDING_REVIEW) {
            throw new BadRequestException(
                    "Request is " + r.getStatus() + ", only PENDING_REVIEW can be approved");
        }
        r.approve(actorUserId, comment);
        DocUpdateRequest saved = repository.save(r);
        log.info("DocUpdateRequest {} APPROVED by user {}", requestId, actorUserId);
        return DocUpdateRequestDto.from(saved);
    }

    @Transactional
    public DocUpdateRequestDto reject(UUID workspaceId, UUID requestId, UUID actorUserId, String comment) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        if (r.getStatus() != DocUpdateStatus.PENDING_REVIEW) {
            throw new BadRequestException(
                    "Request is " + r.getStatus() + ", only PENDING_REVIEW can be rejected");
        }
        r.reject(actorUserId, comment);
        DocUpdateRequest saved = repository.save(r);
        log.info("DocUpdateRequest {} REJECTED by user {}", requestId, actorUserId);
        return DocUpdateRequestDto.from(saved);
    }

    // ── Apply (manager pushes to Git) ───────────────────────────────────

    @Transactional
    public DocUpdateRequestDto apply(UUID workspaceId, UUID requestId, UUID actorUserId) {
        DocUpdateRequest r = loadAndAuthorize(workspaceId, requestId, actorUserId);
        // Only managers can push to Git.
        workspaceService.requireRole(actorUserId, workspaceId, WorkspaceRole.MANAGER);

        if (r.getStatus() != DocUpdateStatus.APPROVED) {
            throw new BadRequestException(
                    "Request is " + r.getStatus() + ", only APPROVED can be applied");
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
        return DocUpdateRequestDto.from(saved);
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
