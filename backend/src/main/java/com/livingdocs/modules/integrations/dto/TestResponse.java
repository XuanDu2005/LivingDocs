package com.livingdocs.modules.integrations.dto;

/**
 * Response from {@code POST /api/v1/admin/integrations/{id}/test}.
 *
 * <p>{@code ok} is null while the test is still in flight (not used by the
 * current implementation, kept for forward compatibility); {@code message}
 * is the raw response from the provider's health endpoint.
 */
public record TestResponse(boolean ok, String message) {
    public static TestResponse success(String message) {
        return new TestResponse(true, message);
    }

    public static TestResponse failure(String message) {
        return new TestResponse(false, message);
    }
}