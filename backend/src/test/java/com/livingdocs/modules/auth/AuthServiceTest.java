package com.livingdocs.modules.auth;

import com.livingdocs.common.security.JwtService;
import com.livingdocs.modules.auth.dto.AuthResponse;
import com.livingdocs.modules.auth.service.AuthService;
import com.livingdocs.modules.auth.service.EmailVerificationService;
import com.livingdocs.modules.auth.service.RefreshTokenService;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.user.dto.LoginRequest;
import com.livingdocs.modules.user.dto.RegisterRequest;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.user.service.UserResponseMapper;
import com.livingdocs.modules.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private AuthService authService;
    private UserRepository userRepository;
    private JwtService jwtService;
    private EmailVerificationService emailVerificationService;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        UserRoleAssignmentRepository roleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserService userService = new UserService(userRepository, roleAssignmentRepository, encoder);
        jwtService = new JwtService("test-secret-which-is-at-least-32-bytes-long-for-hmac-sha256", 60);
        emailVerificationService = mock(EmailVerificationService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        UserResponseMapper userResponseMapper = mock(UserResponseMapper.class);

        // The refresh token and the user projection are part of the
        // response, so the mocks have to produce real values — a Mockito
        // default of null would blow up while assembling the response.
        when(refreshTokenService.issue(any(), any(), any()))
                .thenReturn(new RefreshTokenService.Issued(
                        "refresh-token", OffsetDateTime.now().plusDays(30)));
        when(userResponseMapper.toResponse(any(), any()))
                .thenAnswer(inv -> {
                    User u = inv.getArgument(0);
                    return com.livingdocs.modules.user.dto.UserResponse.from(
                            u, List.of(), List.of(), null);
                });

        authService = new AuthService(userService, roleAssignmentRepository, jwtService, emailVerificationService, refreshTokenService, userResponseMapper, 15);
    }

    @Test
    void register_returnsPendingResponse() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setEmailForTest(UUID.randomUUID());
            u.setCreatedAtForTest(OffsetDateTime.now());
            u.setUpdatedAtForTest(OffsetDateTime.now());
            return u;
        });

        com.livingdocs.modules.auth.controller.AuthController.RegistrationPendingResponse response = authService.register(
                new RegisterRequest("alice@example.com", "secret-password", "Alice"));

        assertNotNull(response);
        assertEquals("alice@example.com", response.email());
        assertEquals(15, response.verificationTtlMinutes());
    }

    @Test
    void login_returnsTokenForValidCredentials() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setEmailForTest(UUID.randomUUID());
            u.setCreatedAtForTest(OffsetDateTime.now());
            u.setUpdatedAtForTest(OffsetDateTime.now());
            return u;
        });
        authService.register(new RegisterRequest("alice@example.com", "secret-password", "Alice"));

        User existing = new User("alice@example.com", "Alice",
                new BCryptPasswordEncoder().encode("secret-password"));
        existing.setEmailForTest(UUID.randomUUID());
        // Login is only allowed for verified accounts — the test predates
        // that requirement, so the flag has to be set explicitly.
        existing.markEmailVerified();
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(existing));

        AuthResponse response = authService.login(
                new LoginRequest("alice@example.com", "secret-password"));

        assertTrue(response.token().length() > 20);
        assertEquals("alice@example.com", response.user().email());
    }
}