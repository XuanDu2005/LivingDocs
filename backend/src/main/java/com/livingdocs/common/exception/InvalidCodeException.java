package com.livingdocs.common.exception;

/**
 * Thrown when an OTP code is wrong, expired or already consumed.
 * Maps to HTTP 400.
 */
public class InvalidCodeException extends RuntimeException {
    public InvalidCodeException(String message) {
        super(message);
    }
}
