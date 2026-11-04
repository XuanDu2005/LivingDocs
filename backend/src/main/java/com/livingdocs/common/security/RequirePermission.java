package com.livingdocs.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or whole class) as requiring one or more
 * <em>permissions</em> rather than platform roles.
 *
 * <p>Permissions are resolved from the {@code role_permissions} table:
 * the caller must hold a platform role that has been granted at least
 * one of the listed permission codes. ADMIN always passes because the
 * matrix pins its row to "all granted".
 *
 * <p>Use this instead of {@link RequirePlatformRole} whenever the
 * capability is not purely a tier question — for example "may read the
 * AI provider configuration" or "may view operational health metrics".
 * A role check cannot express that, and it is why a DEVELOPER used to be
 * able to reach pages that exposed API keys, audit trails and system
 * metrics.
 *
 * <p>Enforcement is handled by {@link PermissionInterceptor}.
 *
 * <p>Example:
 * <pre>{@code
 * @RequirePermission({"AI_CONFIG_READ"})
 * @GetMapping("/api/v1/workspaces/{id}/ai-settings")
 * public AiSettings get(...) {}
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /** Permission codes (UPPER_SNAKE_CASE). The caller must hold at least one. */
    String[] value();
}
