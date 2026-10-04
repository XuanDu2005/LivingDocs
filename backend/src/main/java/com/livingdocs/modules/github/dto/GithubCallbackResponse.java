package com.livingdocs.modules.github.dto;

import java.util.List;

/**
 * Response of the OAuth callback: either the freshly-created
 * connection, or a list of validation errors. Wrapping both
 * outcomes in a single record keeps the API surface stable.
 */
public record GithubCallbackResponse(
        boolean ok,
        String login,
        Long githubUserId,
        String error) {

    public static GithubCallbackResponse success(String login, Long id) {
        return new GithubCallbackResponse(true, login, id, null);
    }

    public static GithubCallbackResponse failure(String error) {
        return new GithubCallbackResponse(false, null, null, error);
    }
}