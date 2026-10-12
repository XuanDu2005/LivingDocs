package com.livingdocs.modules.auth.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.JwtService;
import com.livingdocs.common.security.SecurityUtils;
import com.livingdocs.modules.auth.dto.AuthResponse;
import com.livingdocs.modules.auth.dto.ForgotPasswordRequest;
import com.livingdocs.modules.auth.dto.RefreshTokenRequest;
import com.livingdocs.modules.auth.dto.ResendVerificationRequest;
import com.livingdocs.modules.auth.dto.ResetPasswordRequest;
import com.livingdocs.modules.auth.dto.VerifyEmailRequest;
import com.livingdocs.modules.auth.service.AuthService;
import com.livingdocs.modules.auth.service.EmailVerificationService;
import com.livingdocs.modules.auth.service.PasswordResetService;
import com.livingdocs.modules.auth.service.RefreshTokenService;
import com.livingdocs.modules.user.dto.LoginRequest;
import com.livingdocs.modules.user.dto.RegisterRequest;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * Open (unauthenticated) endpoints that establish a session by issuing
 * a JWT bearer token plus a refresh token. Also exposes the token
 * rotation and logout endpoints.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register, log in, refresh, log out, verify email, and obtain a JWT")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;
    private final com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository roleAssignmentRepository;

    public AuthController(AuthService authService,
                          EmailVerificationService emailVerificationService,
                          PasswordResetService passwordResetService,
                          RefreshTokenService refreshTokenService,
                          UserService userService,
                          com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository roleAssignmentRepository) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.refreshTokenService = refreshTokenService;
        this.userService = userService;
        this.roleAssignmentRepository = roleAssignmentRepository;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    @ApiResponse(responseCode = "202", description = "Account created; verification email sent")
    public ResponseEntity<RegistrationPendingResponse> register(@Valid @RequestBody RegisterRequest req) {
        RegistrationPendingResponse body = authService.register(req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password")
    @ApiResponse(responseCode = "200", description = "Credentials valid, JWT + refresh token issued")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(authService.loginWithContext(req, httpReq));
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm the 6-digit code sent to the user's email")
    @ApiResponse(responseCode = "200", description = "Email verified, JWT + refresh token issued")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest req, HttpServletRequest httpReq) {
        var purpose = req.purpose() == null
                ? com.livingdocs.modules.user.model.EmailVerificationCode.Purpose.REGISTER
                : com.livingdocs.modules.user.model.EmailVerificationCode.Purpose.valueOf(req.purpose().name());
        return ResponseEntity.ok(authService.verifyEmailWithContext(req.email(), req.code(), purpose, httpReq));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access + refresh pair")
    @ApiResponse(responseCode = "200", description = "Rotation successful, new tokens issued")
    @ApiResponse(responseCode = "401", description = "Refresh token is missing, invalid, or expired")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest req, HttpServletRequest httpReq) {
        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(
                req.refreshToken(),
                SecurityUtils.userAgent(httpReq),
                SecurityUtils.ipAddress(httpReq)
        );
        User user = userService.getById(rotated.userId());
        java.util.List<String> roleCodes = roleAssignmentRepository.findActiveRoleCodesByUserId(user.getId());
        JwtService.IssuedToken access = refreshTokenService.issueAccessToken(user.getId(), user.getEmail(), roleCodes);
        return ResponseEntity.ok(new AuthResponse(
                access.token(),
                access.expiresAt().atOffset(ZoneOffset.UTC),
                rotated.newRawToken(),
                rotated.newExpiresAt(),
                UserResponse.from(user, roleCodes)
        ));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke a single refresh token (server-side logout)")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshTokenRequest req) {
        if (req != null && req.refreshToken() != null) {
            refreshTokenService.revoke(req.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke every refresh token for the current user")
    public ResponseEntity<Void> logoutAll() {
        refreshTokenService.revokeAllForUser(CurrentUser.requireId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend-verification")
    @Operation(summary = "Re-send the verification email to a not-yet-verified account")
    @ApiResponse(responseCode = "202", description = "Request accepted; email sent if the account exists")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        emailVerificationService.resend(req.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Send a password-reset code to the account's email")
    @ApiResponse(responseCode = "202", description = "Request accepted; email sent if the account exists")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordResetService.requestReset(req.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Confirm the reset code and set a new password")
    @ApiResponse(responseCode = "204", description = "Password updated")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.reset(req.email(), req.code(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * Returned on successful registration — tells the client the user
     * still needs to confirm their email before they can log in.
     */
    public record RegistrationPendingResponse(
            boolean requiresEmailVerification,
            String email,
            int verificationTtlMinutes
    ) {
    }
}
