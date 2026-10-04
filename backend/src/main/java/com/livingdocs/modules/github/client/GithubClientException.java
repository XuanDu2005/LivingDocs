package com.livingdocs.modules.github.client;

/**
 * Raised by the {@link GithubClient} when GitHub returns an unexpected
 * status, a malformed body, or a documented error envelope. The
 * controller layer translates this to a {@code 502 BAD_GATEWAY} so
 * callers can distinguish upstream failures from their own input
 * validation.
 */
public class GithubClientException extends RuntimeException {
    public GithubClientException(String message) { super(message); }
    public GithubClientException(String message, Throwable cause) { super(message, cause); }
}