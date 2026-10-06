package com.livingdocs.common.email;

/**
 * A single email message ready to be sent.
 *
 * <p>The body is plain text. The implementation is responsible for any
 * HTML rendering or template wrapping.
 */
public record EmailMessage(
        String to,
        String subject,
        String body
) {
}
