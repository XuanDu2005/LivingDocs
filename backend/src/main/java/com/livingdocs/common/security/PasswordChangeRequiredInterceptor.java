package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Refuses every request from an account that still has to replace a
 * temporary password.
 *
 * <p>When an administrator creates an account or resets somebody's
 * password, the account lands in the "must change password" state. The
 * account can log in — it has to, otherwise the person could never
 * reach the screen where they set their own password — but until the
 * temporary credential is replaced it may not do anything else.
 *
 * <p>Without this guard a temporary password that was handed over in
 * a chat window, or written on a sticky note, would remain a working
 * credential for the whole life of the account. The guard is what makes
 * the temporary password a handover device rather than a permanent
 * credential.
 *
 * <p>The check runs on the principal, not on the database: the
 * authentication filter already loaded the user row on this request, so
 * the flag is one field read away.
 *
 * <p>Allowed while the flag is set:
 * <ul>
 *     <li>{@code PUT /api/v1/users/me/password} — replacing the
 *         password is the whole point;</li>
 *     <li>{@code GET /api/v1/users/me} — the client needs to know
 *         <em>that</em> it has to ask for a change (and to render the
 *         prompt);</li>
 *     <li>{@code POST /api/v1/auth/logout} and
 *         {@code POST /api/v1/auth/logout-all} — a person who cannot
 *         use the account must still be able to end the session;</li>
 *     <li>the OpenAPI and health surfaces, which are not
 *         account-scoped.</li>
 * </ul>
 */
@Component
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    /**
     * Paths (exact) that stay reachable while the flag is set.
     */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/v1/users/me",
            "/api/v1/users/me/password",
            "/api/v1/auth/logout",
            "/api/v1/auth/logout-all",
            "/api/v1/auth/refresh"
    );

    /**
     * Prefixes that stay reachable while the flag is set. These are
     * the account-scoped surfaces that must keep working, because the
     * front-end fetches them while rendering the "change your password"
     * prompt.
     */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "/api/v1/users/avatar/"
    );

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            return true;
        }
        if (!principal.isMustChangePassword()) {
            return true;
        }
        String path = request.getRequestURI();
        if (isAllowed(path)) {
            return true;
        }
        throw new ForbiddenException(
                "This account must replace its temporary password before it can be used. "
                        + "Call PUT /api/v1/users/me/password.");
    }

    private boolean isAllowed(String path) {
        if (ALLOWED_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
