package com.livingdocs.modules.admin.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.template.dto.CreateDocTemplateRequest;
import com.livingdocs.modules.template.dto.DocTemplateListResponse;
import com.livingdocs.modules.template.dto.DocTemplateResponse;
import com.livingdocs.modules.template.dto.UpdateDocTemplateRequest;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administrator endpoints for the global template library.
 *
 * <p>Global templates (workspace_id IS NULL) are the defaults that every
 * new workspace inherits. Only administrators can create, edit, or
 * delete them; workspace-level operations are still served by
 * {@link com.livingdocs.modules.template.controller.DocTemplateController}.
 *
 * <p>The "default per docType" toggle is also admin-only — each
 * {@code DocType} (README, API_REFERENCE, ...) can have at most one
 * global default; that default is the one picked by
 * {@link DocTemplateService#resolveDefault(UUID, String)} when a
 * workspace has no override.
 */
@RestController
@RequestMapping("/api/v1/admin/templates")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin Templates", description = "Global (platform) documentation templates")
public class AdminTemplateController {

    private final DocTemplateRepository templateRepository;
    private final DocTemplateService templateService;
    private final TemplateSchema schema;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    public AdminTemplateController(DocTemplateRepository templateRepository,
                                    DocTemplateService templateService,
                                    TemplateSchema schema,
                                    JdbcTemplate jdbc) {
        this.templateRepository = templateRepository;
        this.templateService = templateService;
        this.schema = schema;
        this.jdbc = jdbc;
    }

    @GetMapping
    @Operation(summary = "List every global template (across all doc types and versions)")
    public DocTemplateListResponse list() {
        // The DocTemplateListResponse shape is what the workspace page
        // also returns, but with an empty workspace list and the full
        // global catalogue instead.
        List<DocTemplate> globals = templateRepository.findAllByWorkspaceIdIsNullOrderByDocTypeAscNameAsc();
        return new DocTemplateListResponse(List.of(),
                globals.stream().map(DocTemplateResponse::from).toList());
    }

    @GetMapping("/{templateId}")
    @Operation(summary = "Fetch a single global template (latest version)")
    public DocTemplateResponse get(@PathVariable UUID templateId) {
        DocTemplate t = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (t.getWorkspaceId() != null) {
            throw new NotFoundException("Template is workspace-scoped, not global");
        }
        return DocTemplateResponse.from(t);
    }

    @PostMapping
    @Operation(summary = "Create a new global template (admin only)")
    public ResponseEntity<DocTemplateResponse> create(@Valid @RequestBody CreateDocTemplateRequest req) {
        // Force workspaceId to null regardless of what the caller sent.
        CreateDocTemplateRequest scoped = new CreateDocTemplateRequest(
                null,
                req.name(), req.slug(), req.description(), req.docType(),
                req.bodyJson(),
                req.outputFormat(),
                req.autoGenerateOnCommit(),
                req.autoGenerateOnPr(),
                req.autoGenerateOnMerge(),
                req.isDefault()
        );
        DocTemplateResponse out = templateService.create(CurrentUser.requireId(), scoped);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @PutMapping("/{templateId}")
    @Operation(summary = "Update a global template (creates a new version)")
    public DocTemplateResponse update(@PathVariable UUID templateId,
                                       @Valid @RequestBody UpdateDocTemplateRequest req) {
        // The standard service refuses to update global templates via the
        // workspace endpoint. We work around that by going through the
        // service's own logic but with role checks satisfied.
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (current.getWorkspaceId() != null) {
            throw new NotFoundException("Template is workspace-scoped, not global");
        }
        // The current service.update rejects globals. We delegate the
        // body normalisation and re-implement the insert with a fresh
        // version number so the global library gains full history too.
        return adminUpdateGlobal(current, req);
    }

    @PostMapping("/{templateId}/rollback")
    @Operation(summary = "Roll back a global template to a previous version")
    public DocTemplateResponse rollback(@PathVariable UUID templateId,
                                         @RequestParam("version") int targetVersion) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (current.getWorkspaceId() != null) {
            throw new NotFoundException("Template is workspace-scoped, not global");
        }
        DocTemplate historical = templateRepository.findBySlugAndVersion(current.getSlug(), targetVersion)
                .orElseThrow(() -> new NotFoundException("Template version not found"));
        UUID newId = UUID.randomUUID();
        UUID actor = CurrentUser.requireId();
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, output_format, " +
                        "auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge, " +
                        "is_default, created_by) " +
                        "VALUES (?, NULL, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, newId);
                    ps.setString(2, historical.getName());
                    ps.setString(3, current.getSlug());
                    ps.setString(4, historical.getDescription());
                    ps.setString(5, historical.getDocType());
                    ps.setInt(6, current.getVersion() + 1);
                    ps.setString(7, historical.getBody());
                    ps.setString(8, historical.getOutputFormat().name());
                    ps.setBoolean(9, historical.isAutoGenerateOnCommit());
                    ps.setBoolean(10, historical.isAutoGenerateOnPr());
                    ps.setBoolean(11, historical.isAutoGenerateOnMerge());
                    ps.setBoolean(12, historical.isDefault());
                    ps.setObject(13, actor);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(newId)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + newId));
        return DocTemplateResponse.from(saved);
    }

    @DeleteMapping("/{templateId}")
    @Operation(summary = "Delete a global template (admin only)")
    public ResponseEntity<Void> delete(@PathVariable UUID templateId) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (current.getWorkspaceId() != null) {
            throw new NotFoundException("Template is workspace-scoped, not global");
        }
        templateRepository.delete(current);
        return ResponseEntity.noContent().build();
    }

    /**
     * Promote a global template to be the platform-wide default for its
     * documentation type. Any other global template that currently holds
     * that default loses the flag so the invariant
     * "at most one default per (workspaceId, docType)" holds for the
     * global rows too.
     */
    @PostMapping("/{templateId}/set-default")
    @Operation(summary = "Set this global template as the platform default for its doc type")
    public DocTemplateResponse setDefault(@PathVariable UUID templateId) {
        UUID actor = CurrentUser.requireId();
        DocTemplate target = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (target.getWorkspaceId() != null) {
            throw new NotFoundException("Template is workspace-scoped, not global");
        }
        // Clear any other default for the same docType.
        jdbc.update("UPDATE doc_templates SET is_default = FALSE " +
                        "WHERE workspace_id IS NULL AND doc_type = ? AND id <> ?",
                target.getDocType(), templateId);
        // Promote the target.
        jdbc.update("UPDATE doc_templates SET is_default = TRUE, " +
                        "updated_at = NOW() WHERE id = ?", templateId);
        em.clear();
        DocTemplate saved = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template disappeared"));
        return DocTemplateResponse.from(saved);
    }

    // ----- helpers -----

    private DocTemplateResponse adminUpdateGlobal(DocTemplate current, UpdateDocTemplateRequest req) {
        UUID newId = UUID.randomUUID();
        UUID actor = CurrentUser.requireId();
        // Reuse the canonical normalisation & validation from the service.
        String normalisedBody;
        try {
            normalisedBody = schema.normalise(req.bodyJson());
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Template body is not valid JSON: " + e.getMessage());
        }
        schema.validate(normalisedBody);

        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, output_format, " +
                        "auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge, " +
                        "is_default, created_by) " +
                        "VALUES (?, NULL, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, newId);
                    ps.setString(2, req.name());
                    ps.setString(3, current.getSlug());
                    ps.setString(4, req.description());
                    ps.setString(5, current.getDocType());
                    ps.setInt(6, current.getVersion() + 1);
                    ps.setString(7, normalisedBody);
                    ps.setString(8, req.outputFormat() == null
                            ? current.getOutputFormat().name() : req.outputFormat().name());
                    ps.setBoolean(9, req.autoGenerateOnCommitOrDefault(current.isAutoGenerateOnCommit()));
                    ps.setBoolean(10, req.autoGenerateOnPrOrDefault(current.isAutoGenerateOnPr()));
                    ps.setBoolean(11, req.autoGenerateOnMergeOrDefault(current.isAutoGenerateOnMerge()));
                    ps.setBoolean(12, req.isDefault());
                    ps.setObject(13, actor);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(newId)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + newId));
        return DocTemplateResponse.from(saved);
    }
}
