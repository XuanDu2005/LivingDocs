package com.livingdocs.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Production {@link EmailService} backed by Spring's {@link JavaMailSender}.
 *
 * <p>Activated when {@code app.mail.smtp.host} is set. Otherwise the
 * {@link LoggingEmailService} takes over so local dev still works without
 * any SMTP configuration.
 */
@Service
@ConditionalOnProperty(prefix = "app.mail.smtp", name = "host", matchIfMissing = false)
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailService(JavaMailSender mailSender, EmailProperties properties) {
        this.mailSender = mailSender;
        this.from = properties.getFrom();
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mime = new SimpleMailMessage();
        mime.setFrom(from);
        mime.setTo(message.to());
        mime.setSubject(message.subject());
        mime.setText(message.body());
        mailSender.send(mime);
        log.info("Sent email to {} subject='{}'", message.to(), message.subject());
    }
}
