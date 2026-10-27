package com.livingdocs.modules.user.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.user.dto.UpdateProfileRequest;
import com.livingdocs.modules.user.dto.UserNotificationPreferenceDto;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.service.UserNotificationPreferenceService;
import com.livingdocs.modules.user.service.UserResponseMapper;
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
    private final UserResponseMapper userResponseMapper;
    private final UserNotificationPreferenceService preferenceService;

    public UserController(UserService userService,
                          UserResponseMapper userResponseMapper,
                          UserNotificationPreferenceService preferenceService) {
        this.userService = userService;
        this.userResponseMapper = userResponseMapper;
        this.preferenceService = preferenceService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user")
    public ResponseEntity<UserResponse> me() {
        UUID id = CurrentUser.requireId();
        return ResponseEntity.ok(userResponseMapper.toResponse(userService.getById(id)));
    }

    @PutMapping("/me")
    @Operation(summary = "Update the currently authenticated user's profile")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateProfileRequest req) {
        UUID id = CurrentUser.requireId();
        return ResponseEntity.ok(userResponseMapper.toResponse(userService.updateProfile(id, req)));
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
