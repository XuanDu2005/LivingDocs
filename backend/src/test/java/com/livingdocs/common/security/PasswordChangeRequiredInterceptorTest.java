package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guard that confines an account with a temporary password to the
 * password-change screen.
 *
 * <p>The point of the guard is that a temporary password — one an
 * administrator chose and handed over in a chat window — must not stay
 * a working credential for the life of the account.
 */
class PasswordChangeRequiredInterceptorTest {

    private PasswordChangeRequiredInterceptor interceptor;
    private HttpServletRequest request;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        interceptor = new PasswordChangeRequiredInterceptor();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/workspaces");
    }

    private void authenticate(boolean mustChangePassword) {
        AuthenticatedUser principal = new AuthenticatedUser(
                USER_ID, "someone@example.test", "hash", true,
                List.of("DEVELOPER"), mustChangePassword);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Object handler() {
        return mock(HandlerMethod.class);
    }

    @Test
    void anAccountInGoodStandingPassesThrough() {
        authenticate(false);
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(jakarta.servlet.http.HttpServletResponse.class), handler()));
    }

    @Test
    void anAccountWithATemporaryPasswordIsConfinedToThePasswordChange() {
        authenticate(true);
        assertThrows(ForbiddenException.class,
                () -> interceptor.preHandle(request, mock(jakarta.servlet.http.HttpServletResponse.class), handler()));
    }

    @Test
    void thePasswordChangeItselfIsReachable() {
        authenticate(true);
        when(request.getRequestURI()).thenReturn("/api/v1/users/me/password");
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(jakarta.servlet.http.HttpServletResponse.class), handler()));
    }

    @Test
    void readingYourOwnProfileIsReachable() {
        authenticate(true);
        when(request.getRequestURI()).thenReturn("/api/v1/users/me");
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(jakarta.servlet.http.HttpServletResponse.class), handler()));
    }

    @Test
    void loggingOutIsReachable() {
        authenticate(true);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/logout");
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(jakarta.servlet.http.HttpServletResponse.class), handler()));
    }

    @Test
    void anUnauthenticatedRequestIsNotThisGuardsBusiness() {
        SecurityContextHolder.clearContext();
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(jakarta.servlet.http.HttpServletResponse.class), handler()));
    }
}
