package com.livingdocs.common.exception;

/**
 * Thrown when authentication fails or credentials are invalid.
 *
 * <p>Mapped to HTTP 401 by {@link GlobalExceptionHandler}.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}