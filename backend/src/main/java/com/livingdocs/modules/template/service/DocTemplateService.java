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
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public DocTemplateService(DocTemplateRepository templateRepository,
                              WorkspaceService workspaceService,
                              ObjectMapper objectMapper) {
        this.templateRepository = templateRepository;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
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

        validateBody(req.bodyJson());

        DocTemplate template = new DocTemplate(
                targetWorkspace,
                req.name(),
                normalizedSlug,
                req.description(),
                req.docType(),
                1,
                req.bodyJson(),
                req.isDefault(),
                actorId
        );
        DocTemplate saved = templateRepository.save(template);
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

        validateBody(req.bodyJson());

        int nextVersion = current.getVersion() + 1;
        DocTemplate updated = new DocTemplate(
                current.getWorkspaceId(),
                req.name(),
                current.getSlug(),
                req.description(),
                current.getDocType(),
                nextVersion,
                req.bodyJson(),
                req.isDefault(),
                actorId
        );
        DocTemplate saved = templateRepository.save(updated);
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

        // Create a new version copying the historical content.
        DocTemplate rolled = new DocTemplate(
                current.getWorkspaceId(),
                historical.getName(),
                current.getSlug(),
                historical.getDescription(),
                historical.getDocType(),
                current.getVersion() + 1,
                historical.getBody(),
                historical.isDefault(),
                actorId
        );
        DocTemplate saved = templateRepository.save(rolled);
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

    private void validateBody(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            throw new BadRequestException("Template body is required");
        }
        try {
            JsonNode tree = objectMapper.readTree(bodyJson);
            if (!tree.isObject() && !tree.isArray()) {
                throw new BadRequestException("Template body must be a JSON object or array");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("Template body is not valid JSON: " + e.getMessage());
        }
    }
}