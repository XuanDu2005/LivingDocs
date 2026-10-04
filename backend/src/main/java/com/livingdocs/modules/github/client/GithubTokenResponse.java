package com.livingdocs.modules.github.client;

/**
 * Result of a GitHub OAuth code exchange.
 *
 * @param accessToken opaque bearer token to be stored on the connection
 * @param tokenType   GitHub always returns {@code "bearer"}
 * @param scope       space-separated list of granted scopes
 * @param error       non-null when the exchange failed (e.g. invalid code)
 * @param errorDescription human-readable description of {@link #error}
 */
public record GithubTokenResponse(
        String accessToken,
        String tokenType,
        String scope,
        String error,
        String errorDescription) {

    public boolean isError() {
        return error != null && !error.isBlank();
    }
}