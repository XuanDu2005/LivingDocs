package com.livingdocs.modules.github.exception;

import com.livingdocs.common.exception.UnauthorizedException;

/**
 * Thrown by {@link com.livingdocs.modules.github.client.GithubClient} when
 * GitHub returns 401 Unauthorized (the user's OAuth token has been revoked,
 * expired, or never had the required scopes). Surfaces to the UI as
 * "GitHub connection is no longer valid — please reconnect" and is also
 * caught by the {@code IntegrationHealthService} to create a token-invalid
 * notification.
 */
public class GithubTokenInvalidException extends UnauthorizedException {
    public GithubTokenInvalidException() {
        super("GitHub access token is no longer valid. Please reconnect your GitHub account.");
    }
}
