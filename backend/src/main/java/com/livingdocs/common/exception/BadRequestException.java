package com.livingdocs.common.exception;

/**
 * Thrown when business validation rules fail (separate from bean-validation
 * which is handled automatically). Mapped to HTTP 400.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}