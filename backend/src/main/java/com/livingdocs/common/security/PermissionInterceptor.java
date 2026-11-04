package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Spring MVC interceptor that enforces {@link RequirePermission}.
 *
 * <p>The annotation is checked before the controller runs. If the
 * annotation is absent this interceptor is a no-op — the endpoint keeps
 * whatever guard it already had.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>The principal must be authenticated.</li>
 *   <li>ADMIN always passes (the matrix pins its row to "all granted",
 *       so the platform can never be locked out).</li>
 *   <li>Otherwise the principal's effective permission set — resolved
 *       from {@code role_permissions} via {@link PermissionService} —
 *       must contain at least one of the required codes.</li>
 * </ol>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;

    public PermissionInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequirePermission annotation = hm.getMethodAnnotation(RequirePermission.class);
        if (annotation == null) {
            annotation = hm.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (annotation == null) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new ForbiddenException("Authentication required");
        }
        // ADMIN is granted every permission by the matrix, but check it
        // explicitly so a mis-seeded matrix can never lock the platform
        // out of its own admin surface.
        if (principal.getRoleCodes().contains(AuthorizationService.ROLE_ADMIN)) {
            return true;
        }
        Set<String> required = new HashSet<>(Arrays.asList(annotation.value()));
        Set<String> held = permissionService.effectivePermissions(principal.getId());
        for (String code : required) {
            if (held.contains(code)) {
                return true;
            }
        }
        throw new ForbiddenException(
                "Requires permission: " + String.join(" or ", annotation.value()));
    }
}
