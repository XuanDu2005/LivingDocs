package com.livingdocs.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or whole class) as requiring one or more
 * platform roles on the authenticated principal. Enforcement is handled by
 * {@link PlatformRoleInterceptor} so controllers stay free of imperative
 * role checks.
 *
 * <p>Role codes are matched against the active role set loaded from the
 * {@code user_roles} table at JWT-issuance and authentication time.
 *
 * <p>Example:
 * <pre>{@code
 * @RequirePlatformRole({"ADMIN"})
 * @GetMapping("/api/v1/admin/users")
 * public List<UserResponse> listUsers() {}
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePlatformRole {
    /** Required role codes (uppercase). The principal must have at least one. */
    String[] value();
}