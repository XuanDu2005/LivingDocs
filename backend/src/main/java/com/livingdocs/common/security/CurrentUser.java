package com.livingdocs.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Small accessor that hides the SecurityContext lookup behind a single call.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * Return the authenticated user's database id, or throw
     * {@link com.livingdocs.common.exception.UnauthorizedException} if no
     * authenticated principal is present.
     */
    public static UUID requireId() {
        AuthenticatedUser principal = current();
        if (principal == null) {
            throw new com.livingdocs.common.exception.UnauthorizedException(
                    "Authentication required");
        }
        return principal.getId();
    }

    public static AuthenticatedUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof AuthenticatedUser u ? u : null;
    }
}