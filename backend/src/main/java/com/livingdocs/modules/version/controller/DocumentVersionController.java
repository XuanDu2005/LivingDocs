package com.livingdocs.modules.version.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.version.dto.BulkRollbackRequest;
import com.livingdocs.modules.version.dto.BulkRollbackResponse;
import com.livingdocs.modules.version.dto.CommitSummaryDto;
import com.livingdocs.modules.version.dto.DocumentVersionDiffResponse;
import com.livingdocs.modules.version.dto.DocumentVersionResponse;
import com.livingdocs.modules.version.dto.DocumentVersionSummary;
import com.livingdocs.modules.version.service.BulkRollbackService;
import com.livingdocs.modules.version.service.DocumentVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for browsing the immutable version history of a document.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Versions", description = "Document version history / change log")
public class DocumentVersionController {

    private final DocumentVersionService versionService;
    private final BulkRollbackService bulkRollbackService;

    public DocumentVersionController(DocumentVersionService versionService,
                                    BulkRollbackService bulkRollbackService) {
        this.versionService = versionService;
        this.bulkRollbackService = bulkRollbackService;
    }

    @GetMapping("/documents/{documentId}/versions")
    @Operation(summary = "List all versions of a document (timeline)")
    public List<DocumentVersionSummary> timeline(@PathVariable UUID documentId) {
        return versionService.timeline(CurrentUser.requireId(), documentId);
    }

    @GetMapping("/documents/{documentId}/versions/{versionNumber}")
    @Operation(summary = "Fetch a specific version of a document")
    public DocumentVersionResponse get(@PathVariable UUID documentId,
                                       @PathVariable Integer versionNumber) {
        return versionService.get(CurrentUser.requireId(), documentId, versionNumber);
    }

    @GetMapping("/documents/{documentId}/versions/{versionNumber}/trigger-commit")
    @Operation(summary = "Return the commit that triggered this AI version, if any")
    public CommitSummaryDto triggerCommit(@PathVariable UUID documentId,
                                            @PathVariable Integer versionNumber) {
        return versionService.getTriggerCommit(CurrentUser.requireId(), documentId, versionNumber);
    }

    @GetMapping("/documents/{documentId}/diff")
    @Operation(summary = "Diff two versions of a document")
    public DocumentVersionDiffResponse diff(@PathVariable UUID documentId,
                                            @RequestParam(value = "from", required = false) Integer from,
                                            @RequestParam(value = "to", required = false) Integer to,
                                            @RequestParam(value = "comparedTo", required = false) String comparedTo) {
        // Special-case: "compare latest AI auto-updated version against the currently published version"
        if ("published".equalsIgnoreCase(comparedTo)) {
            return versionService.diffAgainstPublished(CurrentUser.requireId(), documentId);
        }
        if (from == null || to == null) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "Provide both 'from' and 'to' version numbers, or 'comparedTo=published'");
        }
        return versionService.diff(CurrentUser.requireId(), documentId, from, to);
    }

    @PostMapping("/documents/{documentId}/versions/{versionNumber}/publish")
    @Operation(summary = "Publish a specific approved version (Manager only)")
    public void publish(@PathVariable UUID documentId,
                        @PathVariable Integer versionNumber) {
        versionService.publishVersion(CurrentUser.requireId(), documentId, versionNumber);
    }

    /**
     * Dismiss an AI draft without publishing it.
     *
     * <p>The version stays in the timeline for the audit trail but is
     * marked REJECTED, so it leaves the review queue. The author of the
     * draft may dismiss their own draft; a Manager may dismiss any draft
     * in the workspace.
     */
    @DeleteMapping("/documents/{documentId}/versions/{versionNumber}")
    @Operation(summary = "Dismiss (discard) a draft version without publishing it")
    public DocumentVersionResponse dismiss(@PathVariable UUID documentId,
                                           @PathVariable Integer versionNumber) {
        var v = versionService.dismissVersion(CurrentUser.requireId(), documentId, versionNumber);
        return DocumentVersionResponse.from(v);
    }

    @PostMapping("/documents/{documentId}/rollback")
    @Operation(summary = "Roll back a document to a previous version")
    public DocumentVersionResponse rollback(@PathVariable UUID documentId,
                                            @RequestParam("version") Integer version) {
        var v = versionService.rollback(CurrentUser.requireId(), documentId, version);
        return DocumentVersionResponse.from(v);
    }

    /**
     * Roll back a whole collection of documents at once.
     *
     * <p>The collection is selected by an explicit list of document ids,
     * by repository, by documentation type, or by all three combined.
     * With none of them the collection is every document of the
     * workspace.
     *
     * <p>Gated behind {@code DOCUMENT_ROLLBACK} — the same capability the
     * single-document rollback needs — plus workspace membership.
     *
     * <p>The batch is partially successful by design: every document
     * carries its own outcome, and the ones that could not be rolled
     * back (never published, target version never approved) are reported
     * rather than aborting the whole batch.
     */
    @PostMapping("/workspaces/{workspaceId}/documents/bulk-rollback")
    @RequirePermission({"DOCUMENT_ROLLBACK"})
    @Operation(summary = "Roll back a collection of documents (DOCUMENT_ROLLBACK only)")
    public BulkRollbackResponse bulkRollback(@PathVariable UUID workspaceId,
                                             @RequestBody BulkRollbackRequest request) {
        return bulkRollbackService.bulkRollback(CurrentUser.requireId(), workspaceId, request);
    }
}