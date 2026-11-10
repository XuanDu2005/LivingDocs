package com.livingdocs.modules.version.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.version.dto.BulkRollbackRequest;
import com.livingdocs.modules.version.dto.BulkRollbackResponse;
import com.livingdocs.modules.version.dto.DocumentRollbackResult;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Rolls back a whole collection of documents at once.
 *
 * <p>The single-document rollback answers "undo <em>this</em> change".
 * The situation this service answers is different: an automatic update
 * went wrong and landed on every document of a repository, a module or a
 * workspace, and the author has to undo all of them at once. Doing that
 * document by document is tedious, error-prone, and easy to leave half
 * undone.
 *
 * <h2>Partial success</h2>
 * <p>The batch is deliberately <em>not</em> all-or-nothing. A
 * collection may contain a document that was never published, or one
 * whose target version was never approved; such a document cannot be
 * rolled back. Refusing the whole batch because of one such document
 * would leave the remaining documents in the state the author wanted to
 * escape from. Every document therefore carries its own outcome, and the
 * documents that could be rolled back are.
 *
 * <p>Because of that, the batch method itself is not transactional and
 * the per-document rollback runs in its own transaction: a failure
 * halfway through must not undo the rollbacks that already succeeded.
 */
@Service
public class BulkRollbackService {

    private static final Logger log = LoggerFactory.getLogger(BulkRollbackService.class);

    /**
     * The largest number of documents one batch may touch.
     *
     * <p>A rollback creates a version per document, so an unbounded batch
     * would write an unbounded number of rows in one request. The cap
     * keeps the operation a "collection correction" rather than a
     * workspace-wide rewrite.
     */
    public static final int MAX_DOCUMENTS_PER_BATCH = 200;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentVersionService versionService;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;
    private final AuthorizationService authorizationService;

    public BulkRollbackService(DocumentRepository documentRepository,
                                DocumentVersionRepository versionRepository,
                                DocumentVersionService versionService,
                                WorkspaceService workspaceService,
                                PermissionService permissionService,
                                AuditLogService auditLogService,
                                AuthorizationService authorizationService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.versionService = versionService;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
        this.authorizationService = authorizationService;
    }

    /**
     * Roll back every document of the collection.
     *
     * @param actorId     the actor performing the rollback
     * @param workspaceId the workspace the collection lives in
     * @param request     the selection, the strategy and the reason
     */
    public BulkRollbackResponse bulkRollback(UUID actorId,
                                             UUID workspaceId,
                                             BulkRollbackRequest request) {
        // The capability gate comes first: rolling back is a documented
        // author capability, so it needs the permission — not just the
        // "you are a member" rule.
        permissionService.requireAnyPermission(actorId, PermissionCodes.DOCUMENT_ROLLBACK);
        workspaceService.requireMember(actorId, workspaceId);

        List<Document> documents = resolveCollection(workspaceId, request);
        if (documents.isEmpty()) {
            throw new BadRequestException("The collection is empty — there is nothing to roll back");
        }
        if (documents.size() > MAX_DOCUMENTS_PER_BATCH) {
            throw new BadRequestException(
                    "A bulk rollback may touch at most " + MAX_DOCUMENTS_PER_BATCH
                            + " documents (this one covers " + documents.size() + ")");
        }

        BulkRollbackRequest.Strategy strategy = request.strategyOrDefault();
        if (strategy == BulkRollbackRequest.Strategy.VERSION_NUMBER
                && request.targetVersionNumber() == null) {
            throw new BadRequestException(
                    "targetVersionNumber is required for the VERSION_NUMBER strategy");
        }

        List<DocumentRollbackResult> results = new ArrayList<>(documents.size());
        for (Document document : documents) {
            results.add(rollbackOne(actorId, workspaceId, document, strategy, request));
        }

        int rolledBack = (int) results.stream().filter(DocumentRollbackResult::rolledBack).count();
        int skipped = results.size() - rolledBack;

        recordBatchAudit(actorId, workspaceId, strategy, request, results, rolledBack, skipped);
        log.info("Bulk rollback finished: workspace={} total={} rolled_back={} skipped={}",
                workspaceId, results.size(), rolledBack, skipped);

        return new BulkRollbackResponse(results.size(), rolledBack, skipped, results);
    }

    /**
     * Roll back one document of the collection.
     *
     * <p>Every failure of this method is turned into a
     * {@link DocumentRollbackResult} instead of an exception: the point
     * of the batch is that one bad document does not stop the others.
     */
    private DocumentRollbackResult rollbackOne(UUID actorId,
                                               UUID workspaceId,
                                               Document document,
                                               BulkRollbackRequest.Strategy strategy,
                                               BulkRollbackRequest request) {
        // A document of another workspace must never be touched, even if
        // it was named explicitly in the selection.
        if (!workspaceId.equals(document.getWorkspaceId())) {
            return DocumentRollbackResult.failure(document.getId(), document.getTitle(),
                    "The document does not belong to this workspace");
        }
        try {
            DocumentVersion target = resolveTarget(document, strategy, request);
            if (target == null) {
                return DocumentRollbackResult.failure(document.getId(), document.getTitle(),
                        "No version to roll back to");
            }
            String summary = changeSummary(request, target);
            DocumentVersion created = versionService.rollbackToVersion(
                    actorId, document, target, summary);
            return DocumentRollbackResult.success(
                    document.getId(), document.getTitle(),
                    created.getId(), created.getVersionNumber(),
                    target.getVersionNumber());
        } catch (Exception e) {
            // A failure of one document is reported, not thrown: the rest
            // of the collection still has to be rolled back.
            log.warn("Bulk rollback skipped document {}: {}", document.getId(), e.getMessage());
            return DocumentRollbackResult.failure(document.getId(), document.getTitle(),
                    e.getMessage() == null ? "Rollback failed" : e.getMessage());
        }
    }

    /**
     * The version a document is restored to.
     *
     * @return the target version, or {@code null} when the document has
     *         nothing to restore
     */
    private DocumentVersion resolveTarget(Document document,
                                          BulkRollbackRequest.Strategy strategy,
                                          BulkRollbackRequest request) {
        if (strategy == BulkRollbackRequest.Strategy.VERSION_NUMBER) {
            Integer number = request.targetVersionNumber();
            return versionRepository.findByDocumentIdAndVersionNumber(document.getId(), number)
                    .orElse(null);
        }
        return versionService.findPreviousPublishedVersion(document);
    }

    /**
     * The change summary of the created version.
     *
     * <p>The reason of the batch is recorded in every version it
     * creates, so a reader of the timeline of one document can tell a
     * mass correction from an unrelated edit.
     */
    private String changeSummary(BulkRollbackRequest request, DocumentVersion target) {
        String base = "Bulk rollback to version " + target.getVersionNumber();
        String reason = request.reason();
        if (reason == null || reason.isBlank()) {
            return base;
        }
        return base + " — " + reason.trim();
    }

    /**
     * The documents the batch applies to.
     *
     * <p>The three axes are combined with OR, and the result is always
     * intersected with the workspace of the URL.
     */
    private List<Document> resolveCollection(UUID workspaceId, BulkRollbackRequest request) {
        Set<UUID> selected = new LinkedHashSet<>();

        if (request.documentIds() != null && !request.documentIds().isEmpty()) {
            selected.addAll(request.documentIds());
        }
        if (request.repositoryId() != null) {
            documentRepository.findAllByWorkspaceIdAndRepositoryId(workspaceId, request.repositoryId())
                    .forEach(d -> selected.add(d.getId()));
        }
        if (request.docType() != null && !request.docType().isBlank()) {
            documentRepository
                    .findAllByWorkspaceIdAndDocTypeOrderByUpdatedAtDesc(workspaceId, request.docType())
                    .forEach(d -> selected.add(d.getId()));
        }
        if (selected.isEmpty()) {
            // No axis given: the collection is the whole workspace.
            documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId)
                    .forEach(d -> selected.add(d.getId()));
        }

        List<Document> documents = new ArrayList<>();
        for (UUID documentId : selected) {
            Optional<Document> document = documentRepository.findById(documentId);
            document.ifPresent(documents::add);
        }
        return documents;
    }

    /**
     * Record the batch itself in the audit trail.
     *
     * <p>The per-document rollbacks already left their own entries; this
     * one ties them together, so a reader of the workspace trail can see
     * that the corrections were one deliberate batch and not a series of
     * unrelated edits.
     */
    private void recordBatchAudit(UUID actorId,
                                  UUID workspaceId,
                                  BulkRollbackRequest.Strategy strategy,
                                  BulkRollbackRequest request,
                                  List<DocumentRollbackResult> results,
                                  int rolledBack,
                                  int skipped) {
        try {
            String actorRole = actorRoleName(actorId, workspaceId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("strategy", strategy.name());
            payload.put("total", results.size());
            payload.put("rolledBack", rolledBack);
            payload.put("skipped", skipped);
            payload.put("reason", request.reason() == null ? "" : request.reason());
            payload.put("repositoryId",
                    request.repositoryId() == null ? "" : request.repositoryId().toString());
            payload.put("docType", request.docType() == null ? "" : request.docType());
            payload.put("targetVersionNumber",
                    request.targetVersionNumber() == null ? "" : request.targetVersionNumber());
            auditLogService.record(actorId, actorRole, "document.version.bulk_rolled_back",
                    "document", "", workspaceId, null, payload);
        } catch (Exception e) {
            // The audit entry must never abort the batch itself.
            log.warn("Failed to record the bulk rollback entry: {}", e.getMessage());
        }
    }

    /**
     * The role the actor is attributed as in the audit trail.
     *
     * <p>The platform role axis comes first — it is the axis the change
     * log of a document is written in. A user without a platform role
     * falls back to the workspace role, so the entry never says
     * "MEMBER" for somebody who owns the documentation of the workspace.
     */
    private String actorRoleName(UUID actorId, UUID workspaceId) {
        var roles = authorizationService.platformRoles(actorId);
        if (roles.contains(AuthorizationService.ROLE_ADMIN)
                || roles.contains(AuthorizationService.ROLE_MANAGER)
                || roles.contains(AuthorizationService.ROLE_TECHNICAL_LEAD)) {
            return "MANAGER";
        }
        if (roles.contains(AuthorizationService.ROLE_DEVELOPER)) {
            return "DEVELOPER";
        }
        if (roles.contains(AuthorizationService.ROLE_STAFF)) {
            return "STAFF";
        }
        WorkspaceRole workspaceRole = authorizationService.workspaceRole(actorId, workspaceId);
        if (workspaceRole == WorkspaceRole.MANAGER
                || workspaceRole == WorkspaceRole.DOCUMENTATION_OWNER) {
            return "MANAGER";
        }
        return "STAFF";
    }
}
