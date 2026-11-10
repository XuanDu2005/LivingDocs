package com.livingdocs.modules.template.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.template.dto.CreateDocTemplateRequest;
import com.livingdocs.modules.template.dto.DocTemplateListResponse;
import com.livingdocs.modules.template.dto.DocTemplateResponse;
import com.livingdocs.modules.template.dto.UpdateDocTemplateRequest;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.model.OutputFormat;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Manages the documentation template library.
 *
 * <p>Templates come in two flavours:
 * <ul>
 *   <li><b>Global</b> — workspaceId is null. Shipped as part of the platform
 *       default library. Only administrators can edit them.</li>
 *   <li><b>Workspace</b> — owned by a single workspace, editable by the
 *       workspace's managers.</li>
 * </ul>
 *
 * <p>Every save creates a new version (immutable history). The latest version
 * is always returned by default lookups; older versions are reachable via
 * the rollback operation.
 */
@Service
public class DocTemplateService {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateService.class);

    private final DocTemplateRepository templateRepository;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final TemplateSchema schema;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    public DocTemplateService(DocTemplateRepository templateRepository,
                              WorkspaceService workspaceService,
                              ObjectMapper objectMapper,
                              TemplateSchema schema,
                              JdbcTemplate jdbc) {
        this.templateRepository = templateRepository;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
        this.schema = schema;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DocTemplateListResponse list(UUID actorId, UUID workspaceId) {
        if (workspaceId != null) {
            workspaceService.requireMember(actorId, workspaceId);
        }
        List<DocTemplate> workspaceTemplates = workspaceId == null
                ? List.of()
                : templateRepository.findAllByWorkspaceIdOrderByDocTypeAscNameAsc(workspaceId);
        List<DocTemplate> globalTemplates =
                templateRepository.findAllByWorkspaceIdIsNullOrderByDocTypeAscNameAsc();
        return new DocTemplateListResponse(
                workspaceTemplates.stream().map(DocTemplateResponse::from).toList(),
                globalTemplates.stream().map(DocTemplateResponse::from).toList()
        );
    }

    @Transactional(readOnly = true)
    public DocTemplateResponse get(UUID actorId, UUID workspaceId, UUID templateId) {
        DocTemplate t = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (t.getWorkspaceId() != null) {
            workspaceService.requireMember(actorId, t.getWorkspaceId());
        } else if (workspaceId != null) {
            workspaceService.requireMember(actorId, workspaceId);
        }
        return DocTemplateResponse.from(t);
    }

    @Transactional
    public DocTemplateResponse create(UUID actorId, CreateDocTemplateRequest req) {
        UUID targetWorkspace = req.workspaceId();
        if (targetWorkspace == null) {
            // Only system administrators can create global templates.
            // The /admin endpoints guard this; here we just enforce the
            // shape of the request.
        } else {
            // The workspace MANAGER owns the workspace; the workspace
            // DOCUMENTATION_OWNER owns the shape of the documentation,
            // which includes the template library.
            workspaceService.requireDocumentationGovernance(actorId, targetWorkspace);
        }

        requireSupportedOutputFormat(req.outputFormatOrDefault());

        String normalizedSlug = req.slug().toLowerCase();
        if (templateRepository.findBySlugAndVersion(normalizedSlug, 1).isPresent()) {
            throw new ConflictException("A template with this slug already exists");
        }

        String normalisedBody;
        try {
            normalisedBody = schema.normalise(req.bodyJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "Template body is not valid JSON: " + e.getMessage());
        }
        schema.validate(normalisedBody);

        if (req.isDefault()) {
            // A template is created with a brand new id, so nothing can
            // match the "keep this one" filter yet — the intent is to
            // demote every other default for this (workspace, docType)
            // before the row lands, so the unique partial index added in
            // V49 never sees two defaults at once.
            clearOtherDefaults(targetWorkspace, req.docType(), null);
            // The native UPDATE bypassed the persistence context — detach
            // before the insert so a stale is_default value is not written
            // back on flush.
            em.clear();
        }

        // Insert via native SQL so the jsonb column accepts the bound
        // String. Saving through JPA sends it as varchar, which Postgres
        // refuses to coerce. {@code id} is generated by the database.
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, output_format, " +
                        "auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge, " +
                        "is_default, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, id);
                    if (targetWorkspace != null) {
                        ps.setObject(2, targetWorkspace);
                    } else {
                        ps.setNull(2, java.sql.Types.OTHER);
                    }
                    ps.setString(3, req.name());
                    ps.setString(4, normalizedSlug);
                    ps.setString(5, req.description());
                    ps.setString(6, req.docType());
                    ps.setInt(7, 1);
                    ps.setString(8, normalisedBody);
                    ps.setString(9, req.outputFormatOrDefault().name());
                    ps.setBoolean(10, req.autoGenerateOnCommitOrDefault());
                    ps.setBoolean(11, req.autoGenerateOnPrOrDefault());
                    ps.setBoolean(12, req.autoGenerateOnMergeOrDefault());
                    ps.setBoolean(13, req.isDefault());
                    ps.setObject(14, actorId);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + id));
        log.info("Template created: slug={} workspace={}", saved.getSlug(), saved.getWorkspaceId());
        return DocTemplateResponse.from(saved);
    }

    /**
     * Update an existing template. Because templates are versioned, a new
     * version row is created on every call. The previous version remains
     * reachable via {@link #rollback}.
     */
    @Transactional
    public DocTemplateResponse update(UUID actorId, UUID templateId, UpdateDocTemplateRequest req) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));

        if (current.getWorkspaceId() != null) {
            workspaceService.requireDocumentationGovernance(actorId, current.getWorkspaceId());
        } else {
            throw new ForbiddenException("Global templates cannot be edited via this endpoint");
        }

        requireSupportedOutputFormat(req.outputFormat() == null
                ? current.getOutputFormat() : req.outputFormat());

        String normalisedBody;
        try {
            normalisedBody = schema.normalise(req.bodyJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "Template body is not valid JSON: " + e.getMessage());
        }
        schema.validate(normalisedBody);

        int nextVersion = current.getVersion() + 1;
        UUID newId = UUID.randomUUID();

        if (req.isDefault()) {
            // This new version becomes the default for (workspace, docType).
            // Demote every other holder of the flag first — the previous
            // version of this same template included — so the unique
            // partial index from V49 never sees two defaults at once and
            // resolveDefault() cannot keep pointing at a stale version.
            clearOtherDefaults(current.getWorkspaceId(), current.getDocType(), null);
        } else if (current.isDefault()) {
            // The flag was switched off. The previous version must not keep
            // it, otherwise the template would silently stay the default
            // even though the caller asked for the opposite.
            clearSlugDefaults(current.getSlug());
        }

        // The native UPDATE above bypassed the persistence context, so any
        // managed entity would still hold the stale is_default value and
        // could write it back on flush. Detach everything before the insert.
        em.clear();

        // Insert via native SQL so the jsonb column accepts the bound
        // String. Saving through JPA sends it as varchar, which Postgres
        // refuses to coerce. {@code id} is generated by the database.
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, output_format, " +
                        "auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge, " +
                        "is_default, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, newId);
                    if (current.getWorkspaceId() != null) {
                        ps.setObject(2, current.getWorkspaceId());
                    } else {
                        ps.setNull(2, java.sql.Types.OTHER);
                    }
                    ps.setString(3, req.name());
                    ps.setString(4, current.getSlug());
                    ps.setString(5, req.description());
                    ps.setString(6, current.getDocType());
                    ps.setInt(7, nextVersion);
                    ps.setString(8, normalisedBody);
                    ps.setString(9, req.outputFormat() == null
                            ? current.getOutputFormat().name() : req.outputFormat().name());
                    ps.setBoolean(10, req.autoGenerateOnCommitOrDefault(current.isAutoGenerateOnCommit()));
                    ps.setBoolean(11, req.autoGenerateOnPrOrDefault(current.isAutoGenerateOnPr()));
                    ps.setBoolean(12, req.autoGenerateOnMergeOrDefault(current.isAutoGenerateOnMerge()));
                    ps.setBoolean(13, req.isDefault());
                    ps.setObject(14, actorId);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(newId)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + newId));
        log.info("Template updated: slug={} version={}", saved.getSlug(), saved.getVersion());
        return DocTemplateResponse.from(saved);
    }

    /**
     * Promote a workspace template to be <em>the</em> default for its
     * documentation type.
     *
     * <p>The invariant this enforces is "at most one default per
     * (workspace, docType)" — without it {@link #resolveDefault(UUID, String)}
     * would return an arbitrary row when two templates carried the flag.
     * Any other template (and any older version of the same template)
     * that currently holds the flag for this (workspace, docType) loses it
     * in the same transaction.
     *
     * <p>Global templates are rejected here: they are managed through the
     * admin endpoint, which applies the same rule platform-wide.
     */
    @Transactional
    public DocTemplateResponse setDefault(UUID actorId, UUID templateId) {
        DocTemplate target = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (target.getWorkspaceId() == null) {
            throw new ForbiddenException("Use the admin endpoint for global templates");
        }
        workspaceService.requireDocumentationGovernance(actorId, target.getWorkspaceId());

        clearOtherDefaults(target.getWorkspaceId(), target.getDocType(), templateId);
        jdbc.update("UPDATE doc_templates SET is_default = TRUE, updated_at = NOW() WHERE id = ?",
                templateId);

        // The native UPDATE bypassed the persistence context — detach
        // before re-reading so the response reflects the new flag.
        em.clear();
        DocTemplate saved = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template disappeared: " + templateId));
        log.info("Template set as default: id={} workspace={} docType={}",
                templateId, saved.getWorkspaceId(), saved.getDocType());
        return DocTemplateResponse.from(saved);
    }

    /**
     * Demote every template that currently holds the default flag for the
     * given (workspace, docType) pair, except the one identified by
     * {@code keepId}.
     *
     * <p>{@code keepId} may be null, in which case <em>all</em> defaults in
     * the group are cleared — that is what {@link #create} and
     * {@link #update} need, because the row they are about to write does
     * not exist yet.
     *
     * <p>The SQL is written to handle the global scope (workspaceId ==
     * null) as well; the partial unique indexes from V49 cover both cases.
     */
    public void clearOtherDefaults(UUID workspaceId, String docType, UUID keepId) {
        if (workspaceId == null) {
            if (keepId == null) {
                jdbc.update("UPDATE doc_templates SET is_default = FALSE, updated_at = NOW() "
                                + "WHERE workspace_id IS NULL AND doc_type = ? AND is_default = TRUE",
                        docType);
            } else {
                jdbc.update("UPDATE doc_templates SET is_default = FALSE, updated_at = NOW() "
                                + "WHERE workspace_id IS NULL AND doc_type = ? AND is_default = TRUE "
                                + "AND id <> ?",
                        docType, keepId);
            }
        } else {
            if (keepId == null) {
                jdbc.update("UPDATE doc_templates SET is_default = FALSE, updated_at = NOW() "
                                + "WHERE workspace_id = ? AND doc_type = ? AND is_default = TRUE",
                        workspaceId, docType);
            } else {
                jdbc.update("UPDATE doc_templates SET is_default = FALSE, updated_at = NOW() "
                                + "WHERE workspace_id = ? AND doc_type = ? AND is_default = TRUE "
                                + "AND id <> ?",
                        workspaceId, docType, keepId);
            }
        }
    }

    /**
     * Demote every version of a single slug that holds the default flag.
     *
     * <p>Used when the caller switches the flag <em>off</em> on a template
     * that currently carries it: the new version is written without the
     * flag, so the older version must not keep it.
     */
    private void clearSlugDefaults(String slug) {
        jdbc.update("UPDATE doc_templates SET is_default = FALSE, updated_at = NOW() "
                + "WHERE slug = ? AND is_default = TRUE", slug);
    }

    @Transactional
    public DocTemplateResponse rollback(UUID actorId, UUID templateId, int targetVersion) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (current.getWorkspaceId() == null) {
            throw new ForbiddenException("Global templates cannot be rolled back via this endpoint");
        }
        workspaceService.requireDocumentationGovernance(actorId, current.getWorkspaceId());

        DocTemplate historical = templateRepository.findBySlugAndVersion(current.getSlug(), targetVersion)
                .orElseThrow(() -> new NotFoundException("Template version not found"));

        // The rolled-back version inherits the historical default flag. If
        // it is set, every other row holding the default for this
        // (workspace, docType) — the current version of this same slug
        // included — has to be demoted first, otherwise the unique partial
        // index from V49 would reject the insert.
        if (historical.isDefault()) {
            clearOtherDefaults(current.getWorkspaceId(), current.getDocType(), null);
        }

        // The native UPDATE bypassed the persistence context — detach
        // before the insert so a stale is_default value is not written
        // back on flush.
        em.clear();

        // Create a new version copying the historical content. Native
        // SQL again to keep the jsonb cast explicit.
        //
        // The output format and the three auto-generation flags are
        // copied from the historical version as well. They used to be
        // omitted from this statement, which meant a rollback silently
        // reset them to their column defaults — harmless while they were
        // dead metadata, a real bug now that they decide what shape the
        // document gets and when it is regenerated.
        UUID newId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, output_format, " +
                        "auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge, " +
                        "is_default, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, newId);
                    if (current.getWorkspaceId() != null) {
                        ps.setObject(2, current.getWorkspaceId());
                    } else {
                        ps.setNull(2, java.sql.Types.OTHER);
                    }
                    ps.setString(3, historical.getName());
                    ps.setString(4, current.getSlug());
                    ps.setString(5, historical.getDescription());
                    ps.setString(6, historical.getDocType());
                    ps.setInt(7, current.getVersion() + 1);
                    ps.setString(8, historical.getBody());
                    ps.setString(9, historical.getOutputFormat() == null
                            ? OutputFormat.MARKDOWN.name() : historical.getOutputFormat().name());
                    ps.setBoolean(10, historical.isAutoGenerateOnCommit());
                    ps.setBoolean(11, historical.isAutoGenerateOnPr());
                    ps.setBoolean(12, historical.isAutoGenerateOnMerge());
                    ps.setBoolean(13, historical.isDefault());
                    ps.setObject(14, actorId);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(newId)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + newId));
        log.info("Template rolled back: slug={} new_version={} from_version={}",
                saved.getSlug(), saved.getVersion(), targetVersion);
        return DocTemplateResponse.from(saved);
    }

    /**
     * Clone a template into a new, independent template.
     *
     * <p>Global templates (no owning workspace) are cloned <em>into</em> a
     * workspace: the copy is workspace-scoped so the workspace can edit it.
     * The target workspace is the one carried in the request path; when the
     * caller did not specify one we fall back to the first workspace the
     * actor manages.
     *
     * <p>Workspace templates are cloned inside their own workspace, keeping
     * the content but starting a fresh version history at version 1.
     */
    @Transactional
    public DocTemplateResponse clone(UUID actorId, UUID workspaceId, UUID templateId, String newName) {
        DocTemplate source = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Source template not found"));

        // Global templates are cloned INTO a workspace; workspace templates
        // are cloned within their own workspace.
        UUID targetWorkspace;
        if (source.getWorkspaceId() == null) {
            targetWorkspace = resolveCloneTargetWorkspace(actorId, workspaceId);
        } else {
            targetWorkspace = source.getWorkspaceId();
            if (workspaceId != null && !workspaceId.equals(targetWorkspace)) {
                throw new ForbiddenException("Template belongs to a different workspace");
            }
        }
        workspaceService.requireDocumentationGovernance(actorId, targetWorkspace);

        // Generate a new unique slug
        final String baseSlug = slugify(newName);
        String candidate = baseSlug;
        int suffix = 1;
        while (templateRepository.findBySlugAndVersion(candidate, 1).isPresent()) {
            suffix++;
            candidate = baseSlug + "-" + suffix;
        }
        final String newSlug = candidate;

        UUID newId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, output_format, " +
                        "auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge, " +
                        "is_default, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, newId);
                    ps.setObject(2, targetWorkspace);
                    ps.setString(3, newName);
                    ps.setString(4, newSlug);
                    ps.setString(5, source.getDescription());
                    ps.setString(6, source.getDocType());
                    ps.setInt(7, 1);
                    ps.setString(8, source.getBody());
                    ps.setString(9, source.getOutputFormat().name());
                    ps.setBoolean(10, source.isAutoGenerateOnCommit());
                    ps.setBoolean(11, source.isAutoGenerateOnPr());
                    ps.setBoolean(12, source.isAutoGenerateOnMerge());
                    ps.setBoolean(13, false); // cloned templates are not default
                    ps.setObject(14, actorId);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(newId)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + newId));
        log.info("Template cloned: source={} new={} workspace={}",
                templateId, newId, targetWorkspace);
        return DocTemplateResponse.from(saved);
    }

    /**
     * Resolve the workspace a global template should be cloned into.
     *
     * <p>Prefers the workspace supplied by the caller (the path variable of
     * {@code POST /workspaces/{workspaceId}/templates/{templateId}/clone}).
     * When absent, the first workspace the actor manages is used.
     */
    private UUID resolveCloneTargetWorkspace(UUID actorId, UUID requestedWorkspaceId) {
        if (requestedWorkspaceId != null) {
            return requestedWorkspaceId;
        }
        UUID fallback = workspaceService.findFirstManagedWorkspaceId(actorId);
        if (fallback == null) {
            throw new BadRequestException("Clone a global template into a workspace you manage");
        }
        return fallback;
    }

    /**
     * Turn a human readable template name into a URL/DB safe slug.
     *
     * <p>The final length is capped at 120 characters (the column limit for
     * {@code name}, and comfortably below the 140 character limit for
     * {@code slug}). The cap is applied to the <em>slug</em> — the previous
     * implementation used the length of the original string and threw
     * {@link StringIndexOutOfBoundsException} for any name containing
     * characters that get replaced (e.g. "API Reference!").
     */
    private String slugify(String s) {
        String slug = s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "template";
        }
        return slug.length() <= 120 ? slug : slug.substring(0, 120);
    }

    @Transactional
    public void delete(UUID actorId, UUID templateId) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (current.getWorkspaceId() == null) {
            throw new ForbiddenException("Global templates cannot be deleted via this endpoint");
        }
        workspaceService.requireDocumentationGovernance(actorId, current.getWorkspaceId());
        templateRepository.delete(current);
    }

    @Transactional(readOnly = true)
    public DocTemplate resolveDefault(UUID workspaceId, String docType) {
        if (workspaceId != null) {
            return templateRepository
                    .findFirstByWorkspaceIdAndDocTypeAndIsDefaultTrue(workspaceId, docType)
                    .or(() -> templateRepository.findFirstByWorkspaceIdIsNullAndDocTypeAndIsDefaultTrue(docType))
                    .orElse(null);
        }
        return templateRepository
                .findFirstByWorkspaceIdIsNullAndDocTypeAndIsDefaultTrue(docType)
                .orElse(null);
    }

    private void _legacy_validateBody(String bodyJson) {
        // Retained only to anchor the old method body — the active
        // validation now lives in {@link TemplateSchema#validate(String)}.
        if (bodyJson == null || bodyJson.isBlank()) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "Template body is required");
        }
    }

    /**
     * Reject an output format the renderer does not implement.
     *
     * <p>PDF is reserved for a future renderer. Storing it silently
     * would make the template claim something the platform cannot
     * deliver — the document would be markdown while the template says
     * PDF. Rejecting it at the API boundary keeps the promise honest;
     * the UI disables the option for the same reason, and a template
     * that already carries PDF from an older release keeps working
     * (the renderer falls back to markdown and logs a warning).
     *
     * <p>Public because the admin console writes global templates with
     * its own statement and has to apply the same rule.
     */
    public void requireSupportedOutputFormat(OutputFormat format) {
        if (format == null || format.isSupported()) {
            return;
        }
        throw new BadRequestException(
                "Output format " + format.name()
                        + " is not supported yet. Use MARKDOWN or HTML.");
    }
}