package com.livingdocs.modules.auth.service;

import com.livingdocs.common.security.JwtService;
import com.livingdocs.modules.auth.dto.AuthResponse;
import com.livingdocs.modules.user.dto.LoginRequest;
import com.livingdocs.modules.user.dto.RegisterRequest;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes user registration/authentication with JWT issuance.
 */
@Service
public class AuthService {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthService(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        User user = userService.register(req);
        return issue(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userService.authenticate(req.email(), req.password());
        return issue(user);
    }

    private AuthResponse issue(User user) {
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getEmail());
        return new AuthResponse(
                issued.token(),
                issued.expiresAt().atOffset(java.time.ZoneOffset.UTC),
                UserResponse.from(user)
        );
    }
}