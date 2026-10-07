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
 * Spring MVC interceptor that enforces {@link RequirePlatformRole} by
 * comparing the {@link AuthenticatedUser}'s loaded role codes against the
 * annotation's value list.
 *
 * <p>If the annotation is absent, this interceptor is a no-op. Otherwise
 * the principal must be authenticated and own at least one required role.
 */
@Component
public class PlatformRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequirePlatformRole annotation = hm.getMethodAnnotation(RequirePlatformRole.class);
        if (annotation == null) {
            annotation = hm.getBeanType().getAnnotation(RequirePlatformRole.class);
        }
        if (annotation == null) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new ForbiddenException("Authentication required");
        }
        Set<String> granted = new HashSet<>(Arrays.asList(annotation.value()));
        for (String code : principal.getRoleCodes()) {
            if (granted.contains(code)) {
                return true;
            }
        }
        throw new ForbiddenException("Requires platform role: " + String.join(" or ", annotation.value()));
    }
}