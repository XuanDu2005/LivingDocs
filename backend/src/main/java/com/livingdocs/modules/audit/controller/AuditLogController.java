package com.livingdocs.modules.audit.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.audit.dto.AuditLogResponse;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST endpoints for browsing the audit trail.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Audit", description = "Append-only audit log of governance actions")
public class AuditLogController {

    private final AuditLogRepository repository;
    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;

    public AuditLogController(AuditLogRepository repository,
                              WorkspaceService workspaceService,
                              DocumentRepository documentRepository,
                              UserRepository userRepository) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
    }

    /**
     * List the audit trail of a workspace.
     *
     * <p>Paging is supported but optional: without {@code page}/{@code size}
     * the endpoint returns the most recent {@link #DEFAULT_LIMIT} entries
     * so a large audit table can never be loaded in one unbounded
     * query. Callers that need everything should follow the
     * {@code totalPages} cursor.
     * <p>The trail records every governance decision taken in the
     * workspace, so it is gated behind {@code AUDIT_LOG_VIEW}: being a
     * workspace member is not enough to read it.
     *
     * <p>The entries are projected into the same {@link AuditLogResponse}
     * the per-document trail uses — including the resolved actor name.
     * A reader of the trail has to know <em>who</em> acted, and a bare
     * UUID is not an answer; returning the raw entity here while the
     * document trail resolved the names would make the two surfaces of
     * the same trail disagree about their most important column.
     */
    @GetMapping("/workspaces/{workspaceId}/audit-logs")
    @RequirePermission({"AUDIT_LOG_VIEW"})
    @Operation(summary = "List audit logs for a workspace (AUDIT_LOG_VIEW only)")
    public PageResponse list(@PathVariable UUID workspaceId,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "200") int size) {
        workspaceService.requireRole(CurrentUser.requireId(), workspaceId,
                WorkspaceRole.MEMBER);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), DEFAULT_LIMIT);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = repository.findAllByWorkspaceId(workspaceId, pageable);
        return new PageResponse(toResponses(result.getContent()),
                safePage, safeSize, result.getTotalElements());
    }

    /**
     * The complete audit trail of a single document.
     *
     * <p>The MANAGER role is required to "view the complete audit trail
     * of a document (AI-generated, auto-updated, staff-edited) before
     * granting approval". The workspace-wide trail cannot serve that
     * need: it is paginated across every action of the workspace, and
     * the association with the document used to live only inside the
     * JSON payload — so "what happened to this document" could not be
     * asked as a query at all.
     *
     * <p>This endpoint answers exactly the entries that concern one
     * document — the AI generation, the auto-update, the human edits,
     * the Staff verdicts and the Manager decisions — newest first, and
     * only the ones that belong to the workspace in the path.
     *
     * <p>The permission gate is the same {@code AUDIT_LOG_VIEW} the
     * workspace-wide trail uses, plus workspace membership: reading a
     * trail means reading who did what in this workspace's content.
     */
    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/audit-trail")
    @RequirePermission({"AUDIT_LOG_VIEW"})
    @Operation(summary = "The complete audit trail of one document (AUDIT_LOG_VIEW only)")
    public DocumentTrailResponse documentTrail(@PathVariable UUID workspaceId,
                                               @PathVariable UUID documentId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "200") int size) {
        UUID actorId = CurrentUser.requireId();
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        // The document has to belong to the workspace in the path.
        // Otherwise a caller who is a member of two workspaces could
        // read the trail of a document of the other one through the
        // wrong URL.
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new com.livingdocs.common.exception.NotFoundException(
                        "Document not found"));
        if (!document.getWorkspaceId().equals(workspaceId)) {
            throw new com.livingdocs.common.exception.NotFoundException(
                    "Document not found in this workspace");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), DEFAULT_LIMIT);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = repository.findAllByDocumentId(documentId, pageable);
        return new DocumentTrailResponse(
                documentId,
                toResponses(result.getContent()),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Project the audit entries of a page into their API representation.
     *
     * <p>The actor's display name is resolved because a reader of the
     * trail has to know <em>who</em> acted, not just a UUID. The names
     * of all the actors of the page are resolved in one query — resolving
     * them one entry at a time would be a query per row.
     */
    private List<AuditLogResponse> toResponses(List<AuditLog> entries) {
        Map<UUID, String> names = userRepository.findAllById(entries.stream()
                        .map(AuditLog::getActorUserId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                // toMap rejects a null value, so the name is never left
                // null here — the id is the last resort.
                .collect(Collectors.toMap(User::getId, u -> {
                    if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
                        return u.getDisplayName();
                    }
                    if (u.getEmail() != null && !u.getEmail().isBlank()) {
                        return u.getEmail();
                    }
                    return u.getId().toString();
                }));
        return entries.stream()
                .map(entry -> AuditLogResponse.from(entry, names.get(entry.getActorUserId())))
                .toList();
    }

    /** Upper bound on the number of audit rows returned in one response. */
    public static final int DEFAULT_LIMIT = 1000;

    public record PageResponse(List<AuditLogResponse> items, int page, int size, long total) {}

    /**
     * The paged envelope of a document's trail.
     *
     * <p>{@code totalPages} is exposed explicitly so a client can walk
     * the whole trail: the requirement is the <em>complete</em> trail,
     * so the reader must be able to reach the first entry, not only the
     * most recent page.
     */
    public record DocumentTrailResponse(UUID documentId,
                                        List<AuditLogResponse> items,
                                        int page,
                                        int size,
                                        long total,
                                        int totalPages) {}
}
