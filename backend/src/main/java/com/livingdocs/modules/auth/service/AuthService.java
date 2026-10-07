package com.livingdocs.modules.auth.service;

import com.livingdocs.common.exception.EmailNotVerifiedException;
import com.livingdocs.common.security.JwtService;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.auth.controller.AuthController.RegistrationPendingResponse;
import com.livingdocs.modules.auth.dto.AuthResponse;
import com.livingdocs.modules.user.dto.LoginRequest;
import com.livingdocs.modules.user.dto.RegisterRequest;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.model.EmailVerificationCode;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Composes user registration/authentication with JWT issuance and the
 * email-verification handshake.
 */
@Service
public class AuthService {

    private final UserService userService;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;
    private final int verificationTtlMinutes;

    public AuthService(UserService userService,
                       UserRoleAssignmentRepository roleAssignmentRepository,
                       JwtService jwtService,
                       EmailVerificationService emailVerificationService,
                       @Value("${app.auth.email-verification.ttl-minutes:15}") int verificationTtlMinutes) {
        this.userService = userService;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.jwtService = jwtService;
        this.emailVerificationService = emailVerificationService;
        this.verificationTtlMinutes = verificationTtlMinutes;
    }

    /**
     * Register a new user. The account starts with {@code emailVerified=false};
     * a 6-digit code is sent to the address and the client must call
     * {@code POST /auth/verify-email} to obtain a JWT.
     */
    @Transactional
    public RegistrationPendingResponse register(RegisterRequest req) {
        User user = userService.register(req);
        emailVerificationService.issueRegistrationCode(user);
        return new RegistrationPendingResponse(
                true, user.getEmail(), verificationTtlMinutes);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userService.authenticate(req.email(), req.password());
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException(user.getEmail());
        }
        return issue(user);
    }

    /**
     * Confirm a verification code and, on success, return a fresh JWT so
     * the client can navigate straight to the dashboard.
     */
    @Transactional
    public AuthResponse verifyEmail(String email, String code, EmailVerificationCode.Purpose purpose) {
        User user = emailVerificationService.verify(email, code, purpose);
        return issue(user);
    }

    private AuthResponse issue(User user) {
        List<String> roleCodes = roleAssignmentRepository.findActiveRoleCodesByUserId(user.getId());
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getEmail(), roleCodes);
        return new AuthResponse(
                issued.token(),
                issued.expiresAt().atOffset(java.time.ZoneOffset.UTC),
                UserResponse.from(user, roleCodes)
        );
    }
}