package com.livingdocs.modules.github.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link GithubProperties} so it can be injected as a bean
 * throughout the GitHub integration.
 */
@Configuration
@EnableConfigurationProperties(GithubProperties.class)
public class GithubConfig {
}