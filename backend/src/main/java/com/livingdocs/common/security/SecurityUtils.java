package com.livingdocs.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Static helpers for pulling the standard HTTP-context fields we record
 * alongside issued tokens (user-agent for the session list, client IP
 * for rate-limit and audit-log purposes).
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static String userAgent(HttpServletRequest req) {
        if (req == null) return null;
        String h = req.getHeader("User-Agent");
        return h == null ? null : h;
    }

    public static String ipAddress(HttpServletRequest req) {
        if (req == null) return null;
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            int comma = h.indexOf(',');
            return comma > 0 ? h.substring(0, comma).trim() : h.trim();
        }
        return req.getRemoteAddr();
    }
}
