package com.livingdocs.modules.auth.controller;

import com.livingdocs.modules.auth.dto.AuthResponse;
import com.livingdocs.modules.auth.service.AuthService;
import com.livingdocs.modules.user.dto.LoginRequest;
import com.livingdocs.modules.user.dto.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open (unauthenticated) endpoints that establish a session by issuing
 * a JWT bearer token.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register and log in to obtain a JWT")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    @ApiResponse(responseCode = "201", description = "Account created")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        AuthResponse body = authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password")
    @ApiResponse(responseCode = "200", description = "Credentials valid, JWT issued")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }
}