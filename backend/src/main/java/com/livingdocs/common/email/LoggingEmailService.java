package com.livingdocs.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Dev-mode fallback that just logs the message instead of sending it.
 *
 * <p>Activated whenever SMTP is not actually configured
 * ({@code app.mail.smtp.host} blank or unset). Useful for local development
 * and automated tests where you want to read the OTP from the console.
 *
 * <p>Uses {@link ConditionalOnExpression} so the absence is detected via
 * the same expression {@code SmtpEmailService} uses to detect presence —
 * keeping the two beans mutually exclusive without relying on
 * {@code ConditionalOnMissingBean}, which has surprising interactions with
 * other {@code @ConditionalOn*} annotations.
 */
@Service
@ConditionalOnExpression(
        "'${app.mail.smtp.host:}'.isEmpty() OR '${app.mail.smtp.host:}'.isBlank()"
)
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void send(EmailMessage message) {
        log.info("📧 [DEV MODE] Email not sent — would have delivered:");
        log.info("    to:      {}", message.to());
        log.info("    subject: {}", message.subject());
        log.info("    body:    {}", message.body());
    }
}
