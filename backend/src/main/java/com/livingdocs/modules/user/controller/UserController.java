package com.livingdocs.modules.user.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.auth.service.LinkedProvidersMapper;
import com.livingdocs.modules.user.dto.UpdateProfileRequest;
import com.livingdocs.modules.user.dto.UserNotificationPreferenceDto;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.service.UserNotificationPreferenceService;
import com.livingdocs.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated user-facing endpoints (read/update your own profile,
 * notification preferences).
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Authenticated user profile operations")
public class UserController {

    private final UserService userService;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final UserNotificationPreferenceService preferenceService;
    private final LinkedProvidersMapper linkedProvidersMapper;

    public UserController(UserService userService,
                          UserRoleAssignmentRepository roleAssignmentRepository,
                          UserNotificationPreferenceService preferenceService,
                          LinkedProvidersMapper linkedProvidersMapper) {
        this.userService = userService;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.preferenceService = preferenceService;
        this.linkedProvidersMapper = linkedProvidersMapper;
    }

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user")
    public ResponseEntity<UserResponse> me() {
        UUID id = CurrentUser.requireId();
        User user = userService.getById(id);
        List<String> roleCodes = roleAssignmentRepository.findActiveRoleCodesByUserId(id);
        return ResponseEntity.ok(UserResponse.from(user, roleCodes, linkedProvidersMapper.forUser(id)));
    }

    @PutMapping("/me")
    @Operation(summary = "Update the currently authenticated user's profile")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateProfileRequest req) {
        UUID id = CurrentUser.requireId();
        User updated = userService.updateProfile(id, req);
        List<String> roleCodes = roleAssignmentRepository.findActiveRoleCodesByUserId(id);
        return ResponseEntity.ok(UserResponse.from(updated, roleCodes, linkedProvidersMapper.forUser(id)));
    }

    @GetMapping("/me/notification-preferences")
    @Operation(summary = "Get the current user's notification preferences")
    public UserNotificationPreferenceDto getMyNotificationPreferences() {
        return preferenceService.getMine();
    }

    @PutMapping("/me/notification-preferences")
    @Operation(summary = "Update the current user's notification preferences")
    public UserNotificationPreferenceDto updateMyNotificationPreferences(@RequestBody UserNotificationPreferenceDto body) {
        return preferenceService.updateMine(body);
    }
}
