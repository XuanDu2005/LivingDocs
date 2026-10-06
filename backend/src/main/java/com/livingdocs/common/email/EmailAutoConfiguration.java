package com.livingdocs.common.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>The {@link JavaMailSender} bean is only created when SMTP is configured;
 * otherwise callers fall back to the {@link LoggingEmailService}.
 */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.mail.smtp", name = "host")
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
