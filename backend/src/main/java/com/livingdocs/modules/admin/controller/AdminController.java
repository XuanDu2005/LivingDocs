package com.livingdocs.modules.admin.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.SetUserEnabledRequest;
import com.livingdocs.modules.admin.dto.UpdateUserRoleRequest;
import com.livingdocs.modules.admin.service.AdminService;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Administrative endpoints (user / role / workspace configuration).
 *
 * <p>Authorization is enforced by the {@link RequirePlatformRole}
 * annotation: only callers holding the {@code ADMIN} platform role can
 * reach these endpoints.
 */
@RestController
@RequestMapping("/api/v1")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin", description = "Administrative operations")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/admin/users")
    @Operation(summary = "List every user in the system")
    public List<UserResponse> listUsers() {
        return adminService.listUsers().stream()
                .map(u -> UserResponse.from(u, List.of()))
                .toList();
    }

    /**
     * Legacy endpoint kept for backward compatibility. Returns a 409 so
     * callers migrate to {@code PUT /api/v1/admin/users/{userId}/roles}.
     */
    @PutMapping("/admin/users/{userId}/role")
    @Operation(summary = "Deprecated: use PUT /admin/users/{userId}/roles instead")
    public Map<String, Object> updateRole(@PathVariable UUID userId,
                                          @Valid @RequestBody UpdateUserRoleRequest req) {
        User u = adminService.updateRole(CurrentUser.requireId(), userId, req.role());
        return Map.of("id", u.getId().toString(), "role", req.role());
    }

    @PutMapping("/admin/users/{userId}/enabled")
    @Operation(summary = "Enable or disable a platform user")
    public UserResponse setEnabled(@PathVariable UUID userId,
                                   @Valid @RequestBody SetUserEnabledRequest req) {
        User u = adminService.setEnabled(CurrentUser.requireId(), userId, req.enabled());
        return UserResponse.from(u, List.of());
    }
}