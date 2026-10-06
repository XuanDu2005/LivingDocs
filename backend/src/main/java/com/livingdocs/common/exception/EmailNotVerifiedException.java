package com.livingdocs.common.exception;

/**
 * Thrown when a user attempts to log in before verifying their email.
 *
 * <p>Handled by {@code GlobalExceptionHandler} → 401 with a payload that
 * carries the email so the client can offer a "resend verification" CTA.
 */
public class EmailNotVerifiedException extends RuntimeException {

    private final String email;

    public EmailNotVerifiedException(String email) {
        super("Email is not verified");
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
