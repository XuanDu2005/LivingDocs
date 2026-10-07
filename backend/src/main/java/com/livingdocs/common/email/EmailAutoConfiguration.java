package com.livingdocs.common.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Wires up the {@link JavaMailSender} from {@link EmailProperties.Smtp} and
 * registers the {@link EmailProperties} prefix.
 *
 * <p>The {@link JavaMailSender} bean is only created when SMTP is actually
 * configured (host non-blank); otherwise Spring Boot's default
 * {@link JavaMailSender} (pointing at localhost:587) is never created and
 * callers fall back to the {@link LoggingEmailService}.
 *
 * <p>We use {@link ConditionalOnExpression} instead of
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 * so an empty {@code SMTP_HOST} does not count as configured.
 */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailAutoConfiguration {

    @Bean
    @ConditionalOnExpression(
            "!'${app.mail.smtp.host:}'.isEmpty() AND !'${app.mail.smtp.host:}'.isBlank()"
    )
    public JavaMailSender javaMailSender(EmailProperties properties) {
        EmailProperties.Smtp smtp = properties.getSmtp();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.getHost());
        sender.setPort(smtp.getPort());
        sender.setUsername(smtp.getUsername());
        sender.setPassword(smtp.getPassword());
        Properties p = new Properties();
        p.put("mail.smtp.auth", smtp.getUsername() != null && !smtp.getUsername().isBlank());
        p.put("mail.smtp.starttls.enable", smtp.isStarttls());
        sender.setJavaMailProperties(p);
        return sender;
    }
}
