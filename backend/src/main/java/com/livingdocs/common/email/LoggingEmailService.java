package com.livingdocs.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * Dev-mode fallback that just logs the message instead of sending it.
 *
 * <p>Activated whenever no real {@link EmailService} bean is present
 * (i.e. when SMTP is not configured). Useful for local development and
 * automated tests where you want to read the OTP from the console.
 */
@Service
@ConditionalOnMissingBean(EmailService.class)
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
