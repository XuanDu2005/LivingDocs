package com.livingdocs;

import com.livingdocs.common.cache.RedisConfig;
import com.livingdocs.common.oauth.OAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point for the LivingDocs backend.
 *
 * <p>The application is intentionally feature-light at the foundation stage.
 * Only the {@code health} module is active. Each future capability
 * (auth, user, workspace, repository, document, code-analysis, drift,
 * review, version, audit, notification) will be added as a self-contained
 * module under {@code com.livingdocs.modules}.
 *
 * <p>Cross-cutting infrastructure enabled here:
 * <ul>
 *   <li>{@link EnableAsync} — fire-and-forget background work (audit logs,
 *       webhook ingestion, indexing jobs).</li>
 *   <li>{@link EnableScheduling} — cron-style jobs (audit pruning, scheduled
 *       documentation generation).</li>
 *   <li>{@link EnableCaching} — Spring's annotation-driven cache abstraction,
 *       backed by Redis (see {@code RedisConfig}).</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableCaching
@EnableConfigurationProperties({OAuthProperties.class, RedisConfig.CacheProperties.class})
public class LivingDocsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LivingDocsApplication.class, args);
    }
}