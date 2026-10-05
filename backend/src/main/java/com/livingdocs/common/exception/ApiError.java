package com.livingdocs.common.exception;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Standard error response returned by the GlobalExceptionHandler.
 *
 * <p>Stable contract that the client can rely on for every non-2xx response.
 */
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> violations
) {
    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, List.of());
    }

    public static ApiError of(int status, String error, String message, String path,
                              List<FieldViolation> violations) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, violations);
    }
}