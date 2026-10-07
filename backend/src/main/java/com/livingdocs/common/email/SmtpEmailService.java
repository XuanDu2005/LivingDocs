package com.livingdocs.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Production {@link EmailService} backed by Spring's {@link JavaMailSender}.
 *
 * <p>Activated when {@code app.mail.smtp.host} is set AND not blank.
 * Otherwise the {@link LoggingEmailService} takes over so local dev still
 * works without any SMTP configuration.
 *
 * <p><b>Why {@link ConditionalOnExpression} instead of
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}?</b>
 * {@code ConditionalOnProperty} treats an empty string as "set", so an
 * unset {@code SMTP_HOST} env var would resolve to "" (via
 * {@code ${SMTP_HOST:}}) and incorrectly activate this bean. Spring Boot
 * would then auto-configure a default {@code JavaMailSender} pointing at
 * {@code localhost:587} and every email send would fail with a connect
 * timeout. The expression below requires the value to be both present
 * and non-blank.
 */
@Service
@ConditionalOnExpression(
        "!'${app.mail.smtp.host:}'.isEmpty() AND !'${app.mail.smtp.host:}'.isBlank()"
)
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
