package com.livingdocs.common.exception;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
        List<FieldViolation> violations,
        Map<String, Object> metadata
) {
    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, List.of(), Map.of());
    }

    public static ApiError of(int status, String error, String message, String path,
                              List<FieldViolation> violations) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, violations, Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private OffsetDateTime timestamp = OffsetDateTime.now();
        private int status;
        private String error;
        private String message;
        private String path;
        private List<FieldViolation> violations = List.of();
        private Map<String, Object> metadata = Map.of();

        public Builder status(int status) { this.status = status; return this; }
        public Builder error(String error) { this.error = error; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder path(String path) { this.path = path; return this; }
        public Builder violations(List<FieldViolation> violations) { this.violations = violations; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public ApiError build() {
            return new ApiError(timestamp, status, error, message, path, violations, metadata);
        }
    }
}
