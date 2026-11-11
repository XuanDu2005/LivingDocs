package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.AssignRolesRequest;
import com.livingdocs.modules.admin.dto.RoleResponse;
import com.livingdocs.modules.admin.dto.UpdateRoleMetadataRequest;
import com.livingdocs.modules.admin.dto.UserWithRolesResponse;
import com.livingdocs.modules.admin.service.RoleService;
import com.livingdocs.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrator endpoints for the platform role catalogue and per-user role
 * assignments. All endpoints require the {@code ADMIN} platform role.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequirePlatformRole({"ADMIN"})
public class AdminRoleController {

    private final RoleService roleService;

    public AdminRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/roles")
    public List<RoleResponse> listRoles() {
        return roleService.listRoles();
    }

    @GetMapping("/users-with-roles")
    public List<UserWithRolesResponse> listUsersWithRoles() {
        return roleService.listUsersWithRoles();
    }

    /**
     * Edit the presentation of a platform role: its display name, its
     * description and where it sits in the role picker.
     *
     * <p>The role's {@code code} is not editable through this endpoint:
     * it is the identifier that JWTs, audit payloads and permission
     * grants refer to, so changing it would invalidate every
     * assignment pointing at the role.
     */
    @PutMapping("/roles/{roleId}")
    public RoleResponse updateRoleMetadata(@PathVariable UUID roleId,
                                            @Valid @RequestBody UpdateRoleMetadataRequest req) {
        return roleService.updateRoleMetadata(CurrentUser.requireId(), roleId,
                req.name(), req.description(), req.displayOrder());
    }

    @PutMapping("/users/{userId}/roles")
    public Map<String, Object> replaceRoles(@PathVariable UUID userId,
                                            @Valid @RequestBody AssignRolesRequest req) {
        UUID actorId = CurrentUser.requireId();
        List<String> updated = roleService.replaceRoles(actorId, userId, req.roles());
        return Map.of("userId", userId, "roles", updated);
    }
}