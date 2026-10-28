package com.livingdocs.modules.version.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.model.Commit;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.version.dto.CommitSummaryDto;
import com.livingdocs.modules.version.dto.DocumentVersionDiffResponse;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import com.livingdocs.modules.version.dto.DocumentVersionSummary;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages the immutable version history of a document.
 *
 * <p>Three entry-points produce new versions:
 * <ol>
 *     <li>Human edit by the document author.</li>
 *     <li>AI auto-update triggered by a code change.</li>
 *     <li>AI generation of a brand-new document from source.</li>
 * </ol>
 *
 * <p>Every save records the {@link ActorRole} that produced the change so
 * the change log is fully attributable.
 */
@Service
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);

    private final DocumentVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceService workspaceService;
    private final CommitRepository commitRepository;
    private final AuthorizationService authorizationService;

    public DocumentVersionService(DocumentVersionRepository versionRepository,
                                  DocumentRepository documentRepository,
                                  WorkspaceService workspaceService,
                                  CommitRepository commitRepository,
                                  AuthorizationService authorizationService) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
        this.commitRepository = commitRepository;
        this.authorizationService = authorizationService;
    }

    /**
     * Append a new version produced by a human actor (author, staff or manager).
     */
    @Transactional
    public DocumentVersion createHumanVersion(UUID actorId,
                                              UUID documentId,
                                              String bodyMarkdown,
                                              String changeSummary,
                                              ActorRole actorRole) {
        if (actorRole == ActorRole.AI || actorRole == ActorRole.SYSTEM) {
            throw new BadRequestException("Use createAiVersion for AI / system versions");
        }
        Document document = loadAndAuthorize(actorId, documentId);
        int next = nextVersionNumber(documentId);
        DocumentVersion version = new DocumentVersion(
                documentId, next, bodyMarkdown, changeSummary,
                actorRole, actorId,
                null, null, document.getTemplateId(),
                VersionStatus.PENDING, null
        );
        return versionRepository.save(version);
    }

    /**
     * Append a new AI-generated version (initial generation or auto-update).
     */
    @Transactional
    public DocumentVersion createAiVersion(UUID actorUserId,
                                           UUID documentId,
                                           String bodyMarkdown,
                                           String changeSummary,
                                           String sourceCommitSha,
                                           UUID sourcePrId,
                                           Float confidenceScore) {
        return createAiVersion(actorUserId, documentId, bodyMarkdown, changeSummary,
                sourceCommitSha, sourcePrId, confidenceScore, null);
    }

    /**
     * Append a new AI-generated version with grounding evidence persisted.
     */
    @Transactional
    public DocumentVersion createAiVersion(UUID actorUserId,
                                           UUID documentId,
                                           String bodyMarkdown,
                                           String changeSummary,
                                           String sourceCommitSha,
                                           UUID sourcePrId,
                                           Float confidenceScore,
                                           String groundingJson) {
        Document document = loadAndAuthorize(actorUserId, documentId);
        return buildAiVersion(document, actorUserId, documentId, bodyMarkdown, changeSummary,
                sourceCommitSha, sourcePrId, confidenceScore, groundingJson);
    }

    /**
     * Append an AI-generated version from a background worker.
     *
     * <p>Unlike {@link #createAiVersion} this variant does NOT require
     * the actor to be a workspace member: the Kafka listener acts on
     * behalf of the system, and the request that triggered it was
     * already authorised when it was queued.
     *
     * @param actorUserId the user who originally requested the generation;
     *                    may be {@code null} for system-triggered runs
     */
    @Transactional
    public DocumentVersion createAiVersionInternal(UUID actorUserId,
                                                    UUID documentId,
                                                    String bodyMarkdown,
                                                    String changeSummary,
                                                    String sourceCommitSha,
                                                    UUID sourcePrId,
                                                    Float confidenceScore,
                                                    String groundingJson) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        return buildAiVersion(document, actorUserId, documentId, bodyMarkdown, changeSummary,
                sourceCommitSha, sourcePrId, confidenceScore, groundingJson);
    }

    private DocumentVersion buildAiVersion(Document document,
                                           UUID actorUserId,
                                           UUID documentId,
                                           String bodyMarkdown,
                                           String changeSummary,
                                           String sourceCommitSha,
                                           UUID sourcePrId,
                                           Float confidenceScore,
                                           String groundingJson) {
        int next = nextVersionNumber(documentId);
        DocumentVersion version = new DocumentVersion(
                documentId, next, bodyMarkdown, changeSummary,
                ActorRole.AI, actorUserId,
                sourceCommitSha, sourcePrId, document.getTemplateId(),
                VersionStatus.PENDING, confidenceScore
        );
        version.setGroundingJson(groundingJson);
        DocumentVersion saved = versionRepository.save(version);
        log.info("AI version created: document={} version={} confidence={}",
                documentId, next, confidenceScore);
        return saved;
    }

    /**
     * Publish an AI-generated version without a human decision.
     *
     * <p>Used by the governance policy engine: when a documentation type
     * is configured for {@code AUTO_APPLY} and the generation met the
     * workspace's confidence threshold, the version becomes canonical
     * straight away. The background worker acts on behalf of the system,
     * so no membership check happens here — the request that triggered
     * the generation was authorised when it was queued.
     */
    @Transactional
    public void publishAiVersionInternal(UUID documentId, UUID versionId, UUID actorUserId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        DocumentVersion target = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!target.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }
        if (target.getStatus() != VersionStatus.PENDING) {
            // Someone already handled it — nothing to publish.
            log.info("Version {} is already {} — skipping auto-publish",
                    versionId, target.getStatus());
            return;
        }
        promoteToHead(document, target);
        log.info("AI version auto-published: document={} version={}",
                documentId, target.getVersionNumber());
    }

    private void promoteToHead(Document document, DocumentVersion target) {
        // Mark the previous head as SUPERSEDED.
        if (document.getHeadVersionId() != null) {
            versionRepository.findById(document.getHeadVersionId()).ifPresent(prev -> {
                if (!prev.getId().equals(target.getId())) {
                    prev.setStatus(VersionStatus.SUPERSEDED);
                }
            });
        }
        target.setStatus(VersionStatus.PUBLISHED);
        document.setHeadVersionId(target.getId());
        document.setPublishedAt(OffsetDateTime.now());
        document.setStatus(com.livingdocs.modules.document.model.DocumentStatus.PUBLISHED);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersionSummary> timeline(UUID actorId, UUID documentId) {
        loadAndAuthorize(actorId, documentId);
        return versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(documentId)
                .stream()
                .map(v -> DocumentVersionSummary.from(v, shortCommit(v.getSourceCommitSha())))
                .toList();
    }

    private String shortCommit(String sha) {
        if (sha == null || sha.isBlank()) return null;
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    /**
     * Return the body of the currently published version, or the latest
     * version when there is no published head. Used by the AI drift
     * detector when comparing source against documentation.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> findLatestBody(UUID documentId) {
        return versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId)
                .map(DocumentVersion::getBodyMarkdown);
    }

    @Transactional(readOnly = true)
    public DocumentVersionResponse get(UUID actorId, UUID documentId, Integer versionNumber) {
        loadAndAuthorize(actorId, documentId);
        DocumentVersion v = versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        return DocumentVersionResponse.from(v, resolveTriggerCommit(v));
    }

    /**
     * Look up the full commit that triggered an AI version, by SHA. Returns
     * null if no commit matches (e.g. for human edits or older versions
     * that pre-date commit ingestion).
     */
    @Transactional(readOnly = true)
    public CommitSummaryDto getTriggerCommit(UUID actorId, UUID documentId, Integer versionNumber) {
        loadAndAuthorize(actorId, documentId);
        DocumentVersion v = versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        return resolveTriggerCommit(v);
    }

    private CommitSummaryDto resolveTriggerCommit(DocumentVersion v) {
        if (v.getSourceCommitSha() == null || v.getSourceCommitSha().isBlank()) {
            return null;
        }
        Commit commit = commitRepository.findFirstBySha(v.getSourceCommitSha()).orElse(null);
        if (commit == null) {
            // No commit row persisted — synthesise a minimal summary from the SHA so the UI
            // can still render "Triggered by commit abc1234" without a follow-up call.
            String sha = v.getSourceCommitSha();
            return new CommitSummaryDto(
                    null, sha, sha.length() > 7 ? sha.substring(0, 7) : sha,
                    null, null, null, null
            );
        }
        return CommitSummaryDto.from(commit);
    }

    /**
     * Promote a specific version to be the document's head version. Used by
     * the approval workflow after a Manager grants final approval.
     *
     * <p>Requires Manager-tier capability: platform role {@code TECHNICAL_LEAD},
     * {@code MANAGER} or {@code ADMIN} <em>and</em> workspace membership.
     */
    @Transactional
    public void publishVersion(UUID actorId, UUID documentId, Integer versionNumber) {
        Document document = loadAndAuthorize(actorId, documentId);
        authorizationService.requireManagerApprover(actorId, document.getWorkspaceId());
        DocumentVersion target = versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (target.getStatus() != VersionStatus.APPROVED) {
            throw new BadRequestException("Only approved versions can be published");
        }
        promoteToHead(document, target);
    }

    /**
     * Roll back a document to a previously published version. A new version
     * is created (so the history remains append-only) whose body is copied
     * from the chosen historical version. Attributed to the actor invoking
     * the rollback.
     */
    @Transactional
    public DocumentVersion rollback(UUID actorId, UUID documentId, Integer targetVersionNumber) {
        Document document = loadAndAuthorize(actorId, documentId);
        authorizationService.requireManagerApprover(actorId, document.getWorkspaceId());
        DocumentVersion target = versionRepository.findByDocumentIdAndVersionNumber(documentId, targetVersionNumber)
                .orElseThrow(() -> new NotFoundException("Target version not found"));
        if (target.getStatus() != VersionStatus.PUBLISHED && target.getStatus() != VersionStatus.APPROVED) {
            throw new BadRequestException("Only published or approved versions can be rolled back to");
        }
        int next = nextVersionNumber(documentId);
        ActorRole role = actorRoleFor(actorId, document.getWorkspaceId());
        DocumentVersion rollback = new DocumentVersion(
                documentId, next, target.getBodyMarkdown(),
                "Rolled back to version " + targetVersionNumber,
                role, actorId,
                null, null, document.getTemplateId(),
                VersionStatus.PENDING, null
        );
        DocumentVersion saved = versionRepository.save(rollback);
        log.info("Rollback version created: document={} new_version={} target_version={}",
                documentId, next, targetVersionNumber);
        return saved;
    }

    @Transactional(readOnly = true)
    public DocumentVersionDiffResponse diff(UUID actorId, UUID documentId,
                                            Integer fromVersion, Integer toVersion) {
        loadAndAuthorize(actorId, documentId);
        DocumentVersion from = versionRepository.findByDocumentIdAndVersionNumber(documentId, fromVersion)
                .orElseThrow(() -> new NotFoundException("From version not found"));
        DocumentVersion to = versionRepository.findByDocumentIdAndVersionNumber(documentId, toVersion)
                .orElseThrow(() -> new NotFoundException("To version not found"));
        String diff = computeUnifiedDiff(from.getBodyMarkdown(), to.getBodyMarkdown());
        return DocumentVersionDiffResponse.of(from, to, diff);
    }

    /**
     * Compare the most recent AI-authored version against the most recent
     * published version of a document. Used by the "Compare AI vs published"
     * shortcut in the UI.
     */
    @Transactional(readOnly = true)
    public DocumentVersionDiffResponse diffAgainstPublished(UUID actorId, UUID documentId) {
        loadAndAuthorize(actorId, documentId);
        // Find the latest AI-authored version (status any — pending/in_review/approved/published all count)
        DocumentVersion latestAi = versionRepository
                .findAllByDocumentIdOrderByVersionNumberDesc(documentId).stream()
                .filter(v -> v.getActorRole() == ActorRole.AI)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No AI-authored version found for this document"));
        // Find the most recent PUBLISHED version
        DocumentVersion published = versionRepository
                .findAllByDocumentIdOrderByVersionNumberDesc(documentId).stream()
                .filter(v -> v.getStatus() == VersionStatus.PUBLISHED)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No published version found for this document"));
        String diff = computeUnifiedDiff(published.getBodyMarkdown(), latestAi.getBodyMarkdown());
        return DocumentVersionDiffResponse.of(published, latestAi, diff);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private int nextVersionNumber(UUID documentId) {
        return versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(documentId)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
    }

    private Document loadAndAuthorize(UUID actorId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        return document;
    }

    /**
     * Resolve the {@link ActorRole} recorded on a version.
     *
     * <p>Derived from the platform role axis: anyone who can approve
     * (or holds {@code MANAGER}/{@code ADMIN}) is attributed as
     * {@link ActorRole#MANAGER}; everyone else as
     * {@link ActorRole#STAFF}. Falls back to the workspace role when the
     * user has no platform role assignment.
     */
    private ActorRole actorRoleFor(UUID actorId, UUID workspaceId) {
        var roles = authorizationService.platformRoles(actorId);
        if (roles.contains(AuthorizationService.ROLE_ADMIN)
                || roles.contains(AuthorizationService.ROLE_MANAGER)
                || roles.contains(AuthorizationService.ROLE_TECHNICAL_LEAD)) {
            return ActorRole.MANAGER;
        }
        if (roles.contains(AuthorizationService.ROLE_STAFF)
                || roles.contains(AuthorizationService.ROLE_DEVELOPER)) {
            return ActorRole.STAFF;
        }
        var role = workspaceService.requireRole(actorId, workspaceId,
                com.livingdocs.modules.workspace.model.WorkspaceRole.MEMBER);
        return role == com.livingdocs.modules.workspace.model.WorkspaceRole.MANAGER
                ? ActorRole.MANAGER : ActorRole.STAFF;
    }

    /**
     * Compute a minimal unified diff between two strings. For an MVP this is
     * a line-by-line diff using a simple LCS algorithm; production would call
     * a real diff library.
     */
    public static String computeUnifiedDiff(String fromText, String toText) {
        String[] fromLines = fromText == null ? new String[0] : fromText.split("\\R", -1);
        String[] toLines = toText == null ? new String[0] : toText.split("\\R", -1);

        int m = fromLines.length;
        int n = toLines.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (fromLines[i].equals(toLines[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<String> out = new ArrayList<>();
        out.add("--- v" + m);
        out.add("+++ v" + n);
        int i = 0, j = 0;
        while (i < m && j < n) {
            if (fromLines[i].equals(toLines[j])) {
                out.add(" " + fromLines[i]);
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add("-" + fromLines[i]);
                i++;
            } else {
                out.add("+" + toLines[j]);
                j++;
            }
        }
        while (i < m) out.add("-" + fromLines[i++]);
        while (j < n) out.add("+" + toLines[j++]);
        return String.join("\n", out);
    }
}