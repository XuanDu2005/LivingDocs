package com.livingdocs.modules.admin.controller;

import com.livingdocs.common.security.CurrentUser;
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
 * <p>Authorization for these routes is intentionally permissive at the
 * Spring Security layer — the {@link AdminService} verifies that the
 * caller has the privileges required for the specific operation. In a
 * production deployment, restrict access via OAuth2 scopes or a
 * dedicated role check in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Admin", description = "Administrative operations")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/admin/users")
    @Operation(summary = "List every user in the system")
    public List<UserResponse> listUsers() {
        return adminService.listUsers().stream().map(this::toResponse).toList();
    }

    @PutMapping("/admin/users/{userId}/role")
    @Operation(summary = "Update a user's platform role")
    public Map<String, Object> updateRole(@PathVariable UUID userId,
                                          @Valid @RequestBody UpdateUserRoleRequest req) {
        User u = adminService.updateRole(CurrentUser.requireId(), userId, req.role());
        return Map.of("id", u.getId().toString(), "role", req.role());
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(
                u.getId(),
                u.getEmail(),
                u.getDisplayName(),
                u.isEnabled(),
                u.isEmailVerified(),
                u.getCreatedAt());
    }
}