package com.livingdocs.modules.user.model;

import java.util.Locale;

/**
 * OAuth providers the platform knows how to talk to.
 *
 * <p>Backed by the {@code provider} column in {@code user_oauth_identities}
 * and the {@code provider} column in {@code oauth_states}. Keep the
 * {@link #toString()} value in sync with the Flyway {@code CHECK}
 * constraints from migration V4.
 */
public enum OAuthProvider {
    GOOGLE,
    GITHUB;

    /**
     * Lower-case wire format used in URLs and DB columns.
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolve a provider from a wire-format string ("google" / "github").
     *
     * @throws IllegalArgumentException when the value is unknown
     */
    public static OAuthProvider fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("provider is required");
        }
        return OAuthProvider.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
