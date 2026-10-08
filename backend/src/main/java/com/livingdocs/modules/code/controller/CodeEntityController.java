package com.livingdocs.modules.code.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.code.dto.CodeEntityResponse;
import com.livingdocs.modules.code.service.CodeEntityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Code", description = "Indexed code entities")
public class CodeEntityController {

    private final CodeEntityService service;

    public CodeEntityController(CodeEntityService service) {
        this.service = service;
    }

    @GetMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/code-entities")
    @Operation(summary = "List code entities indexed for a repository")
    public List<CodeEntityResponse> list(@PathVariable UUID workspaceId,
                                        @PathVariable UUID repositoryId) {
        return service.listForRepository(CurrentUser.requireId(), workspaceId, repositoryId);
    }

    @GetMapping("/workspaces/{workspaceId}/repositories/{repositoryId}/code-entities/gaps")
    @Operation(summary = "View the code entities that currently have no documentation")
    public List<CodeEntityResponse> listGaps(@PathVariable UUID workspaceId,
                                            @PathVariable UUID repositoryId) {
        return service.listCoverageGaps(CurrentUser.requireId(), workspaceId, repositoryId);
    }

    @GetMapping("/code-entities/{entityId}")
    @Operation(summary = "Fetch a single code entity by id")
    public CodeEntityResponse get(@PathVariable UUID entityId) {
        return service.get(CurrentUser.requireId(), entityId);
    }
}