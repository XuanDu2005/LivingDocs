package com.livingdocs.modules.version.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.common.text.TicketReferences;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.model.Commit;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.version.dto.CommitSummaryDto;
import com.livingdocs.modules.version.dto.DocumentVersionDiffResponse;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import com.livingdocs.modules.version.dto.DocumentVersionSummary;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Parser used to count the grounding sources of a version.
     *
     * <p>Static because it is only used to read a small JSON array and
     * {@code ObjectMapper} is thread-safe once configured.
     */
    private static final ObjectMapper GROUNDING_PARSER = new ObjectMapper();

    private final DocumentVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceService workspaceService;
    private final CommitRepository commitRepository;
    private final PullRequestRepository pullRequestRepository;
    private final AuthorizationService authorizationService;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;

    public DocumentVersionService(DocumentVersionRepository versionRepository,
                                  DocumentRepository documentRepository,
                                  WorkspaceService workspaceService,
                                  CommitRepository commitRepository,
                                  PullRequestRepository pullRequestRepository,
                                  AuthorizationService authorizationService,
                                  PermissionService permissionService,
                                  AuditLogService auditLogService) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
        this.commitRepository = commitRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.authorizationService = authorizationService;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
    }

    /**
     * Append a new version produced by a human actor (author, staff or manager).
     *
     * <p>The {@link ActorRole} is derived from the actor's platform role
     * rather than supplied by the caller: the HTTP layer used to hard-code
     * {@code STAFF}, which mislabelled every DEVELOPER edit in the change
     * log.
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
        ActorRole effective = actorRole != null && actorRole != ActorRole.SYSTEM
                ? actorRole
                : actorRoleFor(actorId, document.getWorkspaceId());
        int next = nextVersionNumber(documentId);
        DocumentVersion version = new DocumentVersion(
                documentId, next, bodyMarkdown, changeSummary,
                effective, actorId,
                null, null, document.getTemplateId(),
                VersionStatus.DRAFT, null
        );
        DocumentVersion saved = versionRepository.save(version);
        // A human edit is one of the three things the audit trail of a
        // document has to show ("AI-generated, auto-updated,
        // staff-edited"). Without this entry the trail only covered the
        // AI path and the review verdicts.
        //
        // The id of a freshly saved row is assigned by the persistence
        // layer, so it is read defensively — a null id must not abort
        // the edit that was just made.
        String versionId = saved.getId() != null ? saved.getId().toString() : "";
        auditLogService.record(actorId, effective.name(), "document.version.created",
                "document_version", versionId,
                document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId,
                        "versionNumber", next,
                        "actorRole", effective.name(),
                        "changeSummary", changeSummary == null ? "" : changeSummary));
        return saved;
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
                VersionStatus.DRAFT, confidenceScore
        );
        version.setGroundingJson(groundingJson);
        // Requirement #2: the grounding panel shows the ticket the
        // change belongs to. The reference lives in the commit message
        // or the pull-request title of the triggering change, so it is
        // extracted on the way in.
        version.setSourceTicketRef(resolveTicketRef(sourceCommitSha, sourcePrId));
        DocumentVersion saved = versionRepository.save(version);
        log.info("AI version created: document={} version={} confidence={}",
                documentId, next, confidenceScore);
        // The audit trail of a document has to show what the AI
        // produced — the requirement names "AI-generated" and
        // "auto-updated" explicitly. The listener used to record this
        // entry, but only for the Kafka path; a version created
        // directly (the entity-generation path) had no entry at all.
        // Recording it here covers every way a version can come into
        // existence, and the two entries are distinguished by the
        // action name.
        recordAiVersionAudit(document, saved, actorUserId, confidenceScore,
                sourceCommitSha, sourcePrId, groundingJson);
        return saved;
    }

    /**
     * Record the audit entry of an AI-authored version.
     *
     * <p>Best-effort by design: a failure to write the audit entry must
     * not undo the version that was just persisted — losing the content
     * would be worse than losing one line of the trail.
     */
    private void recordAiVersionAudit(Document document,
                                      DocumentVersion version,
                                      UUID actorUserId,
                                      Float confidenceScore,
                                      String sourceCommitSha,
                                      UUID sourcePrId,
                                      String groundingJson) {
        try {
            // The id is assigned by the persistence layer; a null id is
            // read defensively so a missing id cannot abort the entry.
            String versionId = version.getId() != null ? version.getId().toString() : "";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("documentId", document.getId().toString());
            payload.put("versionId", versionId);
            payload.put("versionNumber", version.getVersionNumber());
            payload.put("confidence", confidenceScore == null ? 0.0f : confidenceScore);
            payload.put("sourceCommitSha", sourceCommitSha == null ? "" : sourceCommitSha);
            payload.put("sourcePrId", sourcePrId == null ? "" : sourcePrId.toString());
            payload.put("groundingCount", countGroundingSources(groundingJson));
            auditLogService.record(actorUserId, ActorRole.AI.name(),
                    "document.version.ai_created", "document_version",
                    versionId, document.getWorkspaceId(),
                    document.getId(), payload);
        } catch (Exception e) {
            log.warn("Failed to write the audit entry of AI version {}: {}",
                    version.getId(), e.getMessage());
        }
    }

    /**
     * How many grounding sources a version carries.
     *
     * <p>The grounding is stored as a JSON array; only the count is
     * needed for the trail, and counting it must not fail the whole
     * entry when the JSON is unreadable.
     */
    private int countGroundingSources(String groundingJson) {
        if (groundingJson == null || groundingJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = GROUNDING_PARSER.readTree(groundingJson);
            return node != null && node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The ticket reference of the change that triggered a version.
     *
     * <p>Looked up in the commit message first (the commit is the more
     * precise description of the change), then in the pull-request
     * title. Returns {@code null} when neither carries a ticket key —
     * the field is best-effort and a version without a ticket is
     * perfectly valid.
     */
    private String resolveTicketRef(String sourceCommitSha, UUID sourcePrId) {
        if (sourceCommitSha != null && !sourceCommitSha.isBlank()) {
            String fromCommit = commitRepository.findFirstBySha(sourceCommitSha)
                    .map(Commit::getMessage)
                    .map(TicketReferences::first)
                    .orElse(null);
            if (fromCommit != null) {
                return fromCommit;
            }
        }
        if (sourcePrId != null) {
            return pullRequestRepository.findById(sourcePrId)
                    .map(PullRequest::getTitle)
                    .map(TicketReferences::first)
                    .orElse(null);
        }
        return null;
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
        if (target.getStatus() != VersionStatus.DRAFT) {
            // Someone already handled it — nothing to publish. A version
            // that was submitted for review is in PENDING, so this also
            // prevents an auto-publish from racing a human review.
            log.info("Version {} is already {} — skipping auto-publish",
                    versionId, target.getStatus());
            return;
        }
        promoteToHead(document, target);
        // The auto-publish is a distinct step of the trail: it is the
        // point where the AI's output became the published head without
        // a human decision. A reader has to be able to tell it apart
        // from a Manager's publication.
        String versionIdRef = target.getId() != null ? target.getId().toString() : "";
        auditLogService.record(actorUserId, ActorRole.SYSTEM.name(),
                "document.version.auto_published", "document_version",
                versionIdRef, document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionIdRef,
                        "versionNumber", versionNumberOrZero(target)));
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
     * <p>Requires the {@code DOCUMENT_VERSION_PUBLISH} permission — which
     * is a separate capability from {@code DOCUMENT_VERSION_APPROVE} — and
     * workspace membership. Publishing used to reuse the Manager-approval
     * guard, so a role could publish without the matrix saying so.
     */
    @Transactional
    public void publishVersion(UUID actorId, UUID documentId, Integer versionNumber) {
        Document document = loadAndAuthorize(actorId, documentId);
        authorizationService.requireDocumentPublisher(actorId, document.getWorkspaceId());
        DocumentVersion target = versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (target.getStatus() != VersionStatus.APPROVED) {
            throw new BadRequestException("Only approved versions can be published");
        }
        promoteToHead(document, target);
        // Publishing is the moment the content becomes canonical, so it
        // is the single most important entry of the document's trail.
        String versionId = target.getId() != null ? target.getId().toString() : "";
        auditLogService.record(actorId, actorRoleFor(actorId, document.getWorkspaceId()).name(),
                "document.version.published", "document_version",
                versionId, document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId,
                        "versionNumber", versionNumberOrZero(target)));
    }

    /**
     * The version number of a version, never null.
     *
     * <p>{@code Map.of} rejects a null value, so the number is
     * defaulted. A version always carries one in practice — the column
     * is NOT NULL — but a missing number must not abort the audit
     * entry of a publication.
     */
    private int versionNumberOrZero(DocumentVersion version) {
        Integer number = version.getVersionNumber();
        return number == null ? 0 : number;
    }

    /**
     * Discard an AI draft without publishing it.
     *
     * <p>The version is kept for the audit trail but marked
     * {@link VersionStatus#REJECTED}, so it disappears from the review
     * queue while staying visible in the timeline. Only drafts can be
     * dismissed: a version that already went through review or became
     * the document head is part of the published history.
     *
     * <p>Only the author of the draft may dismiss it. Dismissing is an
     * editorial decision about one's own draft, so a Staff reviewer has
     * no business discarding somebody else's work — that is what
     * "request changes" is for. The earlier rule also let a Manager
     * dismiss any draft in the workspace, which was inconsistent with
     * the STAFF role being "DEVELOPER plus review".
     */
    @Transactional
    public DocumentVersion dismissVersion(UUID actorId, UUID documentId, Integer versionNumber) {
        Document document = loadAndAuthorize(actorId, documentId);
        // The capability gate comes first: dismissing a draft is a
        // documented author capability, so it needs the permission and
        // not just the "you authored it" rule below.
        permissionService.requireAnyPermission(actorId, PermissionCodes.DOCUMENT_VERSION_DISMISS);
        DocumentVersion target = versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (target.getStatus() != VersionStatus.DRAFT) {
            throw new BadRequestException(
                    "Only draft versions can be dismissed (this one is " + target.getStatus() + ")");
        }
        boolean isAuthor = target.getActorUserId() != null
                && target.getActorUserId().equals(actorId);
        if (!isAuthor) {
            throw new com.livingdocs.common.exception.ForbiddenException(
                    "You can only dismiss drafts you authored");
        }
        target.setStatus(VersionStatus.REJECTED);
        DocumentVersion saved = versionRepository.save(target);
        String versionId = saved.getId() != null ? saved.getId().toString() : "";
        auditLogService.record(actorId, actorRoleFor(actorId, document.getWorkspaceId()).name(),
                "document.version.dismissed", "document_version",
                versionId, document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId,
                        "versionNumber", versionNumberOrZero(target)));
        log.info("Version dismissed: document={} version={} actor={}",
                documentId, versionNumber, actorId);
        return saved;
    }

    /**
     * Roll back a document to a previously published version. A new version
     * is created (so the history remains append-only) whose body is copied
     * from the chosen historical version. Attributed to the actor invoking
     * the rollback.
     *
     * <p>The spec asks the author to be able to undo an automatic update
     * that produced a wrong result. That is a routine correction of the
     * document's own content, so it only requires the
     * {@code DOCUMENT_ROLLBACK} permission — not the Manager approval
     * tier that gates {@link #publishVersion}.
     *
     * <p>The rollback produces a {@link VersionStatus#DRAFT}, not an
     * {@code APPROVED} version: rolling back to an old body is a
     * correction, and a correction still has to go through the review
     * workflow before it becomes the published head. The document's
     * {@code headVersionId} and status are deliberately left alone — the
     * published head is still the last version that went through review.
     */
    @Transactional
    public DocumentVersion rollback(UUID actorId, UUID documentId, Integer targetVersionNumber) {
        Document document = loadAndAuthorize(actorId, documentId);
        permissionService.requireAnyPermission(actorId, PermissionCodes.DOCUMENT_ROLLBACK);
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
                // Carry the grounding of the version we rolled back to,
                // so the reviewer can still see which commit the content
                // came from.
                target.getSourceCommitSha(), target.getSourcePrId(), document.getTemplateId(),
                VersionStatus.DRAFT, null
        );
        DocumentVersion saved = versionRepository.save(rollback);
        String versionId = saved.getId() != null ? saved.getId().toString() : "";
        auditLogService.record(actorId, role.name(), "document.version.rolled_back",
                "document_version", versionId,
                document.getWorkspaceId(), documentId,
                Map.of("documentId", documentId.toString(),
                        "versionId", versionId,
                        "versionNumber", next,
                        "restoredFromVersion", targetVersionNumber));
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
     * {@link ActorRole#MANAGER}; a {@code DEVELOPER} as
     * {@link ActorRole#DEVELOPER}; everyone else as
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
        if (roles.contains(AuthorizationService.ROLE_DEVELOPER)) {
            return ActorRole.DEVELOPER;
        }
        if (roles.contains(AuthorizationService.ROLE_STAFF)) {
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