package com.livingdocs.modules.ai.config;

import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.ai.settings.AiSettingsService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuration for talking to the LivingDocs AI service.
 *
 * <p>The Spring backend delegates LLM-backed tasks (documentation
 * generation, drift detection, embedding indexing, AST parsing) to the
 * AI service over HTTP. {@link AiServiceProperties} is populated from
 * environment variables prefixed with {@code AI_}.
 */
@Configuration
public class AiServiceConfig {

    @Bean
    @ConfigurationProperties(prefix = "ai-service")
    public AiServiceProperties aiServiceProperties() {
        return new AiServiceProperties();
    }

    @Bean
    public AiServiceClient aiServiceClient(AiServiceProperties props,
                                           AiSettingsService settingsService) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(props.getConnectTimeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(props.getReadTimeoutSeconds()).toMillis());

        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("X-Service-Name", "livingdocs-backend")
                .requestFactory(factory)
                .build();

        return new AiServiceClient(restClient, props, settingsService);
    }
}