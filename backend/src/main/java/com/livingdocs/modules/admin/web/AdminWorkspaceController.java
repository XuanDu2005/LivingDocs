package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.AdminWorkspaceResponse;
import com.livingdocs.modules.admin.service.AdminWorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administrator endpoints for cross-tenant workspace management.
 *
 * <p>Every endpoint requires the {@code ADMIN} platform role. The
 * platform-level guard is enforced by {@link RequirePlatformRole}, so
 * the service layer does not need to repeat the check.
 */
@RestController
@RequestMapping("/api/v1/admin/workspaces")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin · Workspaces", description = "Cross-tenant workspace management")
public class AdminWorkspaceController {

    private final AdminWorkspaceService service;

    public AdminWorkspaceController(AdminWorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List every workspace on the platform")
    public List<AdminWorkspaceResponse> list() {
        return service.listAll();
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "Get a single workspace with member counts")
    public AdminWorkspaceResponse get(@PathVariable UUID workspaceId) {
        return service.getOne(workspaceId);
    }

    @DeleteMapping("/{workspaceId}")
    @Operation(summary = "Delete a workspace on behalf of the platform")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId) {
        service.delete(CurrentUser.requireId(), workspaceId);
        return ResponseEntity.noContent().build();
    }
}