package com.livingdocs.modules.version.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
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

    public DocumentVersionService(DocumentVersionRepository versionRepository,
                                  DocumentRepository documentRepository,
                                  WorkspaceService workspaceService) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
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
        Document document = loadAndAuthorize(actorUserId, documentId);
        int next = nextVersionNumber(documentId);
        DocumentVersion version = new DocumentVersion(
                documentId, next, bodyMarkdown, changeSummary,
                ActorRole.AI, actorUserId,
                sourceCommitSha, sourcePrId, document.getTemplateId(),
                VersionStatus.PENDING, confidenceScore
        );
        DocumentVersion saved = versionRepository.save(version);
        log.info("AI version created: document={} version={} confidence={}",
                documentId, next, confidenceScore);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DocumentVersionSummary> timeline(UUID actorId, UUID documentId) {
        loadAndAuthorize(actorId, documentId);
        return versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(documentId)
                .stream()
                .map(DocumentVersionSummary::from)
                .toList();
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
        return DocumentVersionResponse.from(v);
    }

    /**
     * Promote a specific version to be the document's head version. Used by
     * the approval workflow after a Manager grants final approval.
     */
    @Transactional
    public void publishVersion(UUID actorId, UUID documentId, Integer versionNumber) {
        Document document = loadAndAuthorize(actorId, documentId);
        workspaceService.requireRole(actorId, document.getWorkspaceId(),
                com.livingdocs.modules.workspace.model.WorkspaceRole.MANAGER);
        DocumentVersion target = versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (target.getStatus() != VersionStatus.APPROVED) {
            throw new BadRequestException("Only approved versions can be published");
        }
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

    /**
     * Roll back a document to a previously published version. A new version
     * is created (so the history remains append-only) whose body is copied
     * from the chosen historical version. Attributed to the actor invoking
     * the rollback.
     */
    @Transactional
    public DocumentVersion rollback(UUID actorId, UUID documentId, Integer targetVersionNumber) {
        Document document = loadAndAuthorize(actorId, documentId);
        workspaceService.requireRole(actorId, document.getWorkspaceId(),
                com.livingdocs.modules.workspace.model.WorkspaceRole.MANAGER);
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

    private ActorRole actorRoleFor(UUID actorId, UUID workspaceId) {
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