package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.AdminWorkspaceMemberResponse;
import com.livingdocs.modules.admin.dto.AdminWorkspaceResponse;
import com.livingdocs.modules.admin.service.AdminWorkspaceService;
import com.livingdocs.modules.workspace.dto.AddMemberRequest;
import com.livingdocs.modules.workspace.dto.UpdateMemberRoleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 *
 * <p>The membership endpoints deliberately bypass the
 * {@code MANAGER}-of-this-workspace requirement that the regular
 * {@code /api/v1/workspaces} endpoints enforce: an administrator may
 * not be a member of the workspace they administer, and that must not
 * stop them from fixing its membership. The domain rules that are
 * <em>not</em> about workspace membership — the user has to exist,
 * the owner cannot be demoted below {@code MANAGER} or removed — are
 * still enforced.
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

    // -----------------------------------------------------------------
    // Membership
    // -----------------------------------------------------------------

    @GetMapping("/{workspaceId}/members")
    @Operation(summary = "List the members of a workspace across the tenant boundary")
    public List<AdminWorkspaceMemberResponse> listMembers(@PathVariable UUID workspaceId) {
        return service.listMembers(workspaceId);
    }

    @PostMapping("/{workspaceId}/members")
    @Operation(summary = "Add a member to a workspace on behalf of the platform")
    public ResponseEntity<AdminWorkspaceMemberResponse> addMember(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody AddMemberRequest req) {
        AdminWorkspaceMemberResponse created =
                service.addMember(CurrentUser.requireId(), workspaceId, req);
        return ResponseEntity.ok(created);
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    @Operation(summary = "Change a member's workspace role on behalf of the platform")
    public AdminWorkspaceMemberResponse updateMemberRole(
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateMemberRoleRequest req) {
        return service.updateMemberRole(CurrentUser.requireId(), workspaceId, userId, req);
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @Operation(summary = "Remove a member from a workspace on behalf of the platform")
    public ResponseEntity<Void> removeMember(@PathVariable UUID workspaceId,
                                             @PathVariable UUID userId) {
        service.removeMember(CurrentUser.requireId(), workspaceId, userId);
        return ResponseEntity.noContent().build();
    }
}
