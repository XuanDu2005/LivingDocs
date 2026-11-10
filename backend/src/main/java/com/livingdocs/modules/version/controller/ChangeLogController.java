package com.livingdocs.modules.version.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.version.dto.ChangeLogEntryResponse;
import com.livingdocs.modules.version.dto.ChangeLogFilter;
import com.livingdocs.modules.version.service.ChangeLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The workspace-level change log.
 *
 * <p>Every change of every document of a workspace, with the role that
 * produced it, filterable by role, document, repository and date
 * range. The per-document timeline answers a different question — "what
 * happened to this document" — and neither view can serve the other.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Change log", description = "Workspace-wide change log with role / document / repository filters")
public class ChangeLogController {

    private final ChangeLogService changeLogService;

    public ChangeLogController(ChangeLogService changeLogService) {
        this.changeLogService = changeLogService;
    }

    /**
     * Read the change log of a workspace.
     *
     * <p>Gated behind {@code AUDIT_LOG_VIEW} — the same capability the
     * audit trail requires — plus workspace membership: reading the
     * log means reading who changed what in this workspace.
     *
     * <p>Every filter is optional; an absent one means "do not
     * restrict on this axis".
     */
    @GetMapping("/workspaces/{workspaceId}/change-log")
    @RequirePermission({"AUDIT_LOG_VIEW"})
    @Operation(summary = "List the change log of a workspace (AUDIT_LOG_VIEW only)")
    public PageResponse list(@PathVariable UUID workspaceId,
                             @RequestParam(required = false) UUID documentId,
                             @RequestParam(required = false) UUID repositoryId,
                             @RequestParam(required = false) String actorRole,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                             @RequestParam(required = false) String sort,
                             @RequestParam(required = false) String direction,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        ChangeLogFilter filter = new ChangeLogFilter(
                documentId, repositoryId, actorRole, from, to, sort, direction);
        Page<ChangeLogEntryResponse> result = changeLogService.list(
                CurrentUser.requireId(), workspaceId, filter, page, size);
        return new PageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * The paged envelope of the change log.
     *
     * <p>{@code totalPages} is exposed so a reader can walk the whole
     * log instead of only its most recent page.
     */
    public record PageResponse(List<ChangeLogEntryResponse> items,
                               int page,
                               int size,
                               long total,
                               int totalPages) {}
}
