package com.livingdocs.modules.template.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.template.dto.CreateDocTemplateRequest;
import com.livingdocs.modules.template.dto.DocTemplateListResponse;
import com.livingdocs.modules.template.dto.DocTemplateResponse;
import com.livingdocs.modules.template.dto.UpdateDocTemplateRequest;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * REST endpoints for documentation template management.
 *
 * <p>Workspaces expose CRUD on their own templates. Global templates
 * are read-only through this controller and managed via the admin
 * service.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Templates", description = "Documentation template management")
public class DocTemplateController {

    private final DocTemplateService templateService;
    private final DocTemplateRepository templateRepository;

    public DocTemplateController(DocTemplateService templateService,
                                  DocTemplateRepository templateRepository) {
        this.templateService = templateService;
        this.templateRepository = templateRepository;
    }

    @GetMapping("/workspaces/{workspaceId}/templates")
    @Operation(summary = "List workspace + global templates")
    public DocTemplateListResponse list(@PathVariable UUID workspaceId) {
        return templateService.list(CurrentUser.requireId(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/templates/{templateId}")
    @Operation(summary = "Fetch a template by id")
    public DocTemplateResponse get(@PathVariable UUID workspaceId,
                                   @PathVariable UUID templateId) {
        return templateService.get(CurrentUser.requireId(), workspaceId, templateId);
    }

    @PostMapping("/workspaces/{workspaceId}/templates")
    @Operation(summary = "Create a new workspace template")
    public ResponseEntity<DocTemplateResponse> create(@PathVariable UUID workspaceId,
                                                      @Valid @RequestBody CreateDocTemplateRequest req) {
        CreateDocTemplateRequest scoped = new CreateDocTemplateRequest(
                workspaceId,
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

    @PutMapping("/workspaces/{workspaceId}/templates/{templateId}")
    @Operation(summary = "Update a workspace template (creates a new version)")
    public DocTemplateResponse update(@PathVariable UUID workspaceId,
                                      @PathVariable UUID templateId,
                                      @Valid @RequestBody UpdateDocTemplateRequest req) {
        return templateService.update(CurrentUser.requireId(), templateId, req);
    }

    @PostMapping("/workspaces/{workspaceId}/templates/{templateId}/rollback")
    @Operation(summary = "Roll back a template to a previous version")
    public DocTemplateResponse rollback(@PathVariable UUID workspaceId,
                                        @PathVariable UUID templateId,
                                        @RequestParam("version") int version) {
        return templateService.rollback(CurrentUser.requireId(), templateId, version);
    }

    @GetMapping("/workspaces/{workspaceId}/templates/{templateId}/versions")
    @Operation(summary = "List all versions of a template (history)")
    public List<DocTemplateResponse> listVersions(@PathVariable UUID workspaceId,
                                                   @PathVariable UUID templateId) {
        DocTemplate current = templateRepository.findById(templateId)
                .orElseThrow(() -> new com.livingdocs.common.exception.NotFoundException("Template not found"));
        return templateRepository.findAllBySlugOrderByVersionDesc(current.getSlug())
                .stream()
                .map(DocTemplateResponse::from)
                .toList();
    }

    @PostMapping("/workspaces/{workspaceId}/templates/{templateId}/clone")
    @Operation(summary = "Clone a template into a new template in this workspace")
    public ResponseEntity<DocTemplateResponse> clone(@PathVariable UUID workspaceId,
                                                       @PathVariable UUID templateId,
                                                       @RequestParam("newName") String newName) {
        DocTemplateResponse cloned = templateService.clone(CurrentUser.requireId(), templateId, newName);
        return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
    }

    @DeleteMapping("/workspaces/{workspaceId}/templates/{templateId}")
    @Operation(summary = "Delete a workspace template")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId,
                                       @PathVariable UUID templateId) {
        templateService.delete(CurrentUser.requireId(), templateId);
        return ResponseEntity.noContent().build();
    }
}