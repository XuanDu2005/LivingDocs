package com.livingdocs.modules.template.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.template.dto.CreateDocTemplateRequest;
import com.livingdocs.modules.template.dto.DocTemplateListResponse;
import com.livingdocs.modules.template.dto.DocTemplateResponse;
import com.livingdocs.modules.template.dto.UpdateDocTemplateRequest;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
            workspaceService.requireRole(actorId, targetWorkspace, WorkspaceRole.MANAGER);
        }

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

        // Insert via native SQL so the jsonb column accepts the bound
        // String. Saving through JPA sends it as varchar, which Postgres
        // refuses to coerce. {@code id} is generated by the database.
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, is_default, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
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
                    ps.setBoolean(9, req.isDefault());
                    ps.setObject(10, actorId);
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
            workspaceService.requireRole(actorId, current.getWorkspaceId(), WorkspaceRole.MANAGER);
        } else {
            throw new ForbiddenException("Global templates cannot be edited via this endpoint");
        }

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
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, is_default, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
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
                    ps.setBoolean(9, req.isDefault());
                    ps.setObject(10, actorId);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(newId)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + newId));
        log.info("Template updated: slug={} version={}", saved.getSlug(), saved.getVersion());
        return DocTemplateResponse.from(saved);
    }

    @Transactional
    public DocTemplateResponse rollback(UUID actorId, UUID templateId, int targetVersion) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (current.getWorkspaceId() == null) {
            throw new ForbiddenException("Global templates cannot be rolled back via this endpoint");
        }
        workspaceService.requireRole(actorId, current.getWorkspaceId(), WorkspaceRole.MANAGER);

        DocTemplate historical = templateRepository.findBySlugAndVersion(current.getSlug(), targetVersion)
                .orElseThrow(() -> new NotFoundException("Template version not found"));

        // Create a new version copying the historical content. Native
        // SQL again to keep the jsonb cast explicit.
        UUID newId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_templates (" +
                        "id, workspace_id, name, slug, description, doc_type, " +
                        "version, body, is_default, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
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
                    ps.setBoolean(9, historical.isDefault());
                    ps.setObject(10, actorId);
                });
        em.clear();
        DocTemplate saved = templateRepository.findById(newId)
                .orElseThrow(() -> new IllegalStateException("Insert failed for " + newId));
        log.info("Template rolled back: slug={} new_version={} from_version={}",
                saved.getSlug(), saved.getVersion(), targetVersion);
        return DocTemplateResponse.from(saved);
    }

    @Transactional
    public void delete(UUID actorId, UUID templateId) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        if (current.getWorkspaceId() == null) {
            throw new ForbiddenException("Global templates cannot be deleted via this endpoint");
        }
        workspaceService.requireRole(actorId, current.getWorkspaceId(), WorkspaceRole.MANAGER);
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
}