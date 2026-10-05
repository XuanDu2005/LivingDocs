package com.livingdocs.common.exception;

/**
 * Thrown when an authenticated user attempts to perform an action they
 * are not allowed to perform.
 *
 * <p>Mapped to HTTP 403 by {@link GlobalExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}