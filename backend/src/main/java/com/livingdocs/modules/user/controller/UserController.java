package com.livingdocs.modules.user.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.user.dto.UpdateProfileRequest;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.model.User;
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
 * Authenticated user-facing endpoints (read/update your own profile).
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Authenticated user profile operations")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user")
    public ResponseEntity<UserResponse> me() {
        UUID id = CurrentUser.requireId();
        User user = userService.getById(id);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @PutMapping("/me")
    @Operation(summary = "Update the currently authenticated user's profile")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateProfileRequest req) {
        UUID id = CurrentUser.requireId();
        User updated = userService.updateProfile(id, req);
        return ResponseEntity.ok(UserResponse.from(updated));
    }
}