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
import java.util.Collections;
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
        String header = request.getHeader(AppConstants.AUTH_HEADER);
        if (header != null && header.startsWith(AppConstants.AUTH_SCHEME)) {
            String token = header.substring(AppConstants.AUTH_SCHEME.length()).trim();
            if (!token.isEmpty()) {
                try {
                    Claims claims = jwtService.parse(token);
                    UUID userId = UUID.fromString(claims.getSubject());
                    String email = claims.get(AppConstants.JWT_CLAIM_EMAIL, String.class);

                    boolean enabled = userRepository.findById(userId)
                            .map(u -> u.isEnabled())
                            .orElse(true);
                    // Prefer roles embedded in the JWT (cheap); fall back to
                    // a DB lookup when the claim is missing (e.g. tokens
                    // issued before this code shipped).
                    List<String> roleCodes = extractRoleCodes(claims);
                    if (roleCodes.isEmpty()) {
                        roleCodes = roleAssignmentRepository.findActiveRoleCodesByUserId(userId);
                    }

                    AuthenticatedUser principal = new AuthenticatedUser(userId, email, "", enabled, roleCodes);
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
        }
        chain.doFilter(request, response);
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractRoleCodes(Claims claims) {
        Object raw = claims.get(AppConstants.JWT_CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(Object::toString)
                    .toList();
        }
        return Collections.emptyList();
    }
}