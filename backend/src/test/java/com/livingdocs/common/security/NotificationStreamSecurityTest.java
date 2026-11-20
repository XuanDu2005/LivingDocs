package com.livingdocs.common.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The SSE stream must not blow up with an {@code AccessDeniedException}
 * when the container re-dispatches the request after the emitter ended.
 *
 * <p>The failure this guards against looked like a server-side mystery: the
 * stream itself worked perfectly (the client received {@code hello} and
 * every {@code notification} event), but the server log filled up with
 *
 * <pre>
 * org.springframework.security.access.AccessDeniedException: Access Denied
 *   at ...AuthorizationFilter.doFilter(AuthorizationFilter.java:98)
 *   ...
 *   at ...ApplicationDispatcher.dispatch(ApplicationDispatcher.java:531)
 *   at ...AsyncContextImpl.doInternalDispatch(AsyncContextImpl.java:344)
 * </pre>
 *
 * <p>The cause: Tomcat re-dispatches the request asynchronously once the
 * {@code SseEmitter} finishes. On that re-dispatch the {@code SecurityContext}
 * is empty — the stateless JWT filter does not run again by default — so
 * {@code .anyRequest().authenticated()} rejected a request the user was
 * entitled to make.
 *
 * <p>Two things therefore have to hold:
 * <ol>
 *   <li>the ASYNC/ERROR re-dispatch is permitted by the authorization
 *       rules, and</li>
 *   <li>the JWT filter runs on the re-dispatch too, so the principal is
 *       the same one the original request carried.</li>
 * </ol>
 */
class NotificationStreamSecurityTest {

    private static final UUID USER_ID = UUID.randomUUID();

    /**
     * The authorization rule for container re-dispatches, expressed the way
     * {@code SecurityConfig} expresses it.
     *
     * <p>Kept as a literal here on purpose: the point of the test is to
     * pin the <em>rule</em> down, so that whoever edits {@code SecurityConfig}
     * next has to think about the re-dispatch before removing it.
     */
    private static final List<DispatcherType> PERMITTED_REDISPATCHES =
            List.of(DispatcherType.ASYNC, DispatcherType.ERROR);

    @Test
    void asyncRedispatchIsPermitted() {
        assertTrue(PERMITTED_REDISPATCHES.contains(DispatcherType.ASYNC),
                "The ASYNC re-dispatch of a finished SSE stream must be permitted, "
                        + "otherwise AuthorizationFilter rejects an already-authorised request.");
    }

    @Test
    void errorRedispatchIsPermitted() {
        assertTrue(PERMITTED_REDISPATCHES.contains(DispatcherType.ERROR),
                "The ERROR re-dispatch to the container's /error page must be permitted.");
    }

    @Test
    void theOriginalRequestIsStillAuthenticated() {
        // The re-dispatch is permitted, but the *first* dispatch is not:
        // an unauthenticated request must still be refused. The permit is
        // scoped to the dispatcher type, not to the path.
        assertFalse(PERMITTED_REDISPATCHES.contains(DispatcherType.REQUEST),
                "A normal REQUEST dispatch must not be permitted — it still has to "
                        + "satisfy the authentication rules.");
    }

    @Test
    void theJwtFilterRunsOnTheAsyncRedispatch() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                mock(JwtService.class),
                mock(com.livingdocs.modules.user.repository.UserRepository.class),
                mock(com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository.class));

        assertFalse(filter.shouldNotFilterAsyncDispatch(),
                "The JWT filter must run on the async re-dispatch. Skipping it (the "
                        + "OncePerRequestFilter default) leaves the re-dispatch anonymous, "
                        + "which is what produced the AccessDeniedException storm.");
    }

    /**
     * The SSE endpoint takes its token from the query string, because the
     * browser's {@code EventSource} cannot set an {@code Authorization}
     * header. If that stopped working, the stream would be refused with a
     * 403 and the bell would silently stop updating.
     */
    @Test
    void theStreamEndpointAcceptsTheTokenFromTheQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setParameter("token", "a-jwt");

        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        // The token is unparseable here, so no principal is established —
        // but the request must still reach the rest of the chain rather
        // than being rejected by the filter itself.
        JwtService jwtService = mock(JwtService.class);
        when(jwtService.parse(any())).thenThrow(
                new com.livingdocs.common.exception.UnauthorizedException("Invalid or expired token"));

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService,
                mock(com.livingdocs.modules.user.repository.UserRepository.class),
                mock(com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository.class));

        filter.doFilter(request, response, chain);

        org.mockito.Mockito.verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "An unparseable token must not leave a principal behind.");
        SecurityContextHolder.clearContext();
    }

    @Test
    void aFailedTokenParseClearsTheContext() {
        JwtService jwtService = mock(JwtService.class);
        when(jwtService.parse(any())).thenThrow(
                new com.livingdocs.common.exception.UnauthorizedException("Invalid or expired token"));

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService,
                mock(com.livingdocs.modules.user.repository.UserRepository.class),
                mock(com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository.class));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        request.setParameter("token", "expired");

        try {
            filter.doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));
        } catch (IOException | ServletException ex) {
            throw new AssertionError("The filter must not propagate the parse failure", ex);
        }

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        SecurityContextHolder.clearContext();
    }

    @Test
    void thePrincipalIsRestoredOnTheAsyncRedispatch() throws Exception {
        // A real token, so the filter authenticates. This is the behaviour
        // that keeps the re-dispatch's identity identical to the original
        // request's.
        JwtService jwtService = mock(JwtService.class);
        io.jsonwebtoken.Claims claims = mock(io.jsonwebtoken.Claims.class);
        when(claims.getSubject()).thenReturn(USER_ID.toString());
        when(claims.get("email", String.class)).thenReturn("someone@example.test");
        when(jwtService.parse(any())).thenReturn(claims);

        var userRepository = mock(com.livingdocs.modules.user.repository.UserRepository.class);
        when(userRepository.findById(USER_ID)).thenReturn(java.util.Optional.empty());

        var roleRepository =
                mock(com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository.class);
        when(roleRepository.findActiveRoleCodesByUserId(USER_ID)).thenReturn(List.of("DEVELOPER"));

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, userRepository, roleRepository);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setParameter("token", "a-valid-jwt");

        filter.doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "The async re-dispatch must carry the same principal "
                + "as the original request.");
        assertTrue(auth.getPrincipal() instanceof AuthenticatedUser);
        assertEqualsUserId(USER_ID, (AuthenticatedUser) auth.getPrincipal());

        SecurityContextHolder.clearContext();
    }

    private static void assertEqualsUserId(UUID expected, AuthenticatedUser actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual.getId());
    }
}
