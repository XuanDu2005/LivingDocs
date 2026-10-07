package com.livingdocs;

import com.livingdocs.common.oauth.OAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Application entry point for the LivingDocs backend.
 *
 * <p>The application is intentionally feature-light at the foundation stage.
 * Only the {@code health} module is active. Each future capability
 * (auth, user, workspace, repository, document, code-analysis, drift,
 * review, version, audit, notification) will be added as a self-contained
 * module under {@code com.livingdocs.modules}.
 */
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(OAuthProperties.class)
public class LivingDocsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LivingDocsApplication.class, args);
    }
}