package com.livingdocs.common.security;

import com.livingdocs.modules.workspace.service.WorkspaceCiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates a CI request that presents a workspace CI token.
 *
 * <p>The merge gate is enforced by the CI job calling back into the
 * platform. A workflow has no user session, so it presents the
 * workspace's CI token in the {@code X-LivingDocs-CI-Token} header
 * instead of a JWT.
 *
 * <p>The token resolves to exactly one workspace. The filter binds that
 * workspace id to the request as an attribute, so the merge-gate
 * endpoint can act <em>only</em> on the workspace the token belongs to.
 * A caller cannot name a different workspace: the workspace comes from
 * the token, never from the request body.
 *
 * <p>This filter does not establish a {@code SecurityContext} — a CI
 * token is not a user. The endpoints it protects are listed in the
 * security config's {@code permitAll()} list and authorise themselves
 * against the workspace attribute this filter sets.
 */
public class CiTokenAuthenticationFilter extends OncePerRequestFilter {

    /** The header a CI job presents its token in. */
    public static final String CI_TOKEN_HEADER = "X-LivingDocs-CI-Token";

    /** The request attribute carrying the authenticated workspace id. */
    public static final String CI_WORKSPACE_ATTRIBUTE =
            "com.livingdocs.ci.workspaceId";

    private final WorkspaceCiTokenService ciTokenService;

    public CiTokenAuthenticationFilter(WorkspaceCiTokenService ciTokenService) {
        this.ciTokenService = ciTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(CI_TOKEN_HEADER);
        if (token != null && !token.isBlank()) {
            Optional<UUID> workspaceId = ciTokenService.resolveWorkspace(token);
            if (workspaceId.isPresent()) {
                request.setAttribute(CI_WORKSPACE_ATTRIBUTE, workspaceId.get());
            } else {
                logUnknownToken(request);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * A CI job presented a token the platform does not know. The
     * request is not rejected here — the endpoint it reaches decides
     * what an unauthenticated CI call means. Logging it here makes a
     * rotated or revoked token visible in the server log.
     */
    private void logUnknownToken(HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(CiTokenAuthenticationFilter.class)
                .warn("CI request to {} presented an unknown token",
                        request.getRequestURI());
    }

    /**
     * The workspace the CI token authenticates, or {@code null} when
     * the request did not present a valid token.
     */
    public static UUID authenticatedWorkspace(HttpServletRequest request) {
        Object attr = request.getAttribute(CI_WORKSPACE_ATTRIBUTE);
        return attr instanceof UUID id ? id : null;
    }
}
