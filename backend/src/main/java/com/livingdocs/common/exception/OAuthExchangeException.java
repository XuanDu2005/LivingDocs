package com.livingdocs.common.exception;

/**
 * Thrown when the OAuth provider returns an error, refuses the code,
 * or when the user-info call fails. Maps to HTTP 502 (bad gateway).
 */
public class OAuthExchangeException extends RuntimeException {
    public OAuthExchangeException(String message) {
        super(message);
    }

    public OAuthExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
