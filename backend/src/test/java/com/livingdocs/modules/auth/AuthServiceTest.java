package com.livingdocs.modules.auth;

import com.livingdocs.common.security.JwtService;
import com.livingdocs.modules.auth.dto.AuthResponse;
import com.livingdocs.modules.auth.service.AuthService;
import com.livingdocs.modules.user.dto.LoginRequest;
import com.livingdocs.modules.user.dto.RegisterRequest;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
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

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserService userService = new UserService(userRepository, encoder);
        jwtService = new JwtService("test-secret-which-is-at-least-32-bytes-long-for-hmac-sha256", 60);
        authService = new AuthService(userService, jwtService);
    }

    @Test
    void register_returnsTokenAndUser() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setEmailForTest(UUID.randomUUID());
            u.setCreatedAtForTest(OffsetDateTime.now());
            u.setUpdatedAtForTest(OffsetDateTime.now());
            return u;
        });

        AuthResponse response = authService.register(
                new RegisterRequest("alice@example.com", "secret-password", "Alice"));

        assertNotNull(response.token());
        assertFalse(response.token().isEmpty());
        assertNotNull(response.expiresAt());
        assertEquals("alice@example.com", response.user().email());
        assertEquals("Alice", response.user().displayName());
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
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(existing));

        AuthResponse response = authService.login(
                new LoginRequest("alice@example.com", "secret-password"));

        assertTrue(response.token().length() > 20);
        assertEquals("alice@example.com", response.user().email());
    }
}