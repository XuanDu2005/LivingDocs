package com.livingdocs.common.exception;

/**
 * Thrown when a request is refused because a quota or budget limit has
 * been reached — for example an AI token budget, a rate limit, or a
 * CI/CD merge gate.
 *
 * <p>Mapped to HTTP 429 by {@link GlobalExceptionHandler}.
 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
