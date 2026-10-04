package com.livingdocs.common.security;

import com.livingdocs.common.AppConstants;
import com.livingdocs.common.exception.UnauthorizedException;
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
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
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

                    AuthenticatedUser principal = new AuthenticatedUser(userId, email, "", true);
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
}