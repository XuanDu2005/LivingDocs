package com.livingdocs.common.exception;

/**
 * Thrown when a request collides with a unique constraint or attempts to
 * create a duplicate resource (for example, registering with an email
 * that already exists).
 *
 * <p>Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}