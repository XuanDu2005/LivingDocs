package com.livingdocs.common.exception;

/**
 * Thrown when a requested resource cannot be found.
 *
 * <p>Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}