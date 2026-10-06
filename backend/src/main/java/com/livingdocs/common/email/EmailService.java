package com.livingdocs.common.email;

/**
 * Abstraction over outbound email delivery.
 *
 * <p>Two implementations are available:
 * <ul>
 *   <li>{@link SmtpEmailService} — production, requires SMTP config.</li>
 *   <li>{@link LoggingEmailService} — dev fallback, just logs the message.</li>
 * </ul>
 */
public interface EmailService {
    void send(EmailMessage message);
}
