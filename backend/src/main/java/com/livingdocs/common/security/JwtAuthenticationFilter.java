package com.livingdocs.common.security;

import com.livingdocs.common.AppConstants;
import com.livingdocs.common.exception.UnauthorizedException;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Extracts and validates the JWT bearer token from each request, and
 * populates {@link SecurityContextHolder} with an
 * {@link AuthenticatedUser} when the token is valid.
 *
 * <p>Unauthenticated requests are allowed to continue so that Spring
 * Security's authorization rules can decide whether to permit them.
 *
 * <p>This class is intentionally NOT a {@code @Component}; it is built
 * inside {@link SecurityConfig} so that Spring Boot does not auto-register
 * it as a servlet filter outside the security filter chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtService jwtService,
                                   UserRepository userRepository,
                                   UserRoleAssignmentRepository roleAssignmentRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && !token.isEmpty()) {
            try {
                Claims claims = jwtService.parse(token);
                UUID userId = UUID.fromString(claims.getSubject());
                String email = claims.get(AppConstants.JWT_CLAIM_EMAIL, String.class);

                // The account row is read on every request anyway (the
                // enabled flag decides whether the principal may act at
                // all), so the "must replace a temporary password" flag
                // is read from the same row.
                //
                // Reading it here rather than from a JWT claim is what
                // makes a password reset take effect immediately: a token
                // issued before the reset would otherwise keep the old
                // "account is in good standing" assumption for its whole
                // lifetime.
                var account = userRepository.findById(userId);
                boolean enabled = account.map(u -> u.isEnabled()).orElse(true);
                boolean mustChangePassword = account
                        .map(u -> u.isMustChangePassword())
                        .orElse(false);
                // The active role codes are always read from the database,
                // never from the token's `roles` claim.
                //
                // The claim was a snapshot taken when the token was
                // issued. An administrator who promotes an account from
                // DEVELOPER to STAFF would otherwise keep the stale
                // claim for the whole lifetime of the token (up to 24
                // hours), and every `@RequirePlatformRole`-guarded
                // endpoint would keep judging the account by its old
                // role. The permission matrix has the same problem and
                // solves it by evicting its cache; the role list has no
                // cache, so the lookup is simply always made.
                List<String> roleCodes =
                        roleAssignmentRepository.findActiveRoleCodesByUserId(userId);

                AuthenticatedUser principal = new AuthenticatedUser(
                        userId, email, "", enabled, roleCodes, mustChangePassword);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContext context = securityContextHolderStrategy.getContext();
                context.setAuthentication(auth);
                securityContextHolderStrategy.setContext(context);
            } catch (UnauthorizedException | IllegalArgumentException ex) {
                securityContextHolderStrategy.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Pull the JWT from either the standard {@code Authorization}
     * header or, for the SSE endpoint, a {@code ?token=} query string
     * parameter. The browser's {@code EventSource} cannot set custom
     * headers, so SSE clients pass the token in the URL.
     */
    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AppConstants.AUTH_HEADER);
        if (header != null && header.startsWith(AppConstants.AUTH_SCHEME)) {
            return header.substring(AppConstants.AUTH_SCHEME.length()).trim();
        }
        String path = request.getRequestURI();
        if (path != null && path.endsWith("/notifications/stream")) {
            return request.getParameter("token");
        }
        return null;
    }
}