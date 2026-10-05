package com.livingdocs.common;

/**
 * Application-wide constants. Keep this list short; only truly cross-cutting
 * values belong here. Module-specific constants stay inside their module.
 */
public final class AppConstants {

    public static final String API_V1_PREFIX = "/api/v1";
    public static final String SERVICE_NAME = "livingdocs-backend";

    /** Custom JWT claim carrying the user's email address. */
    public static final String JWT_CLAIM_EMAIL = "email";

    /** Authentication scheme used in HTTP Authorization headers. */
    public static final String AUTH_HEADER = "Authorization";
    public static final String AUTH_SCHEME = "Bearer ";

    private AppConstants() {
        // utility class
    }
}