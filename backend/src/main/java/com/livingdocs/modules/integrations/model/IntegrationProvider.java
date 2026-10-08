package com.livingdocs.modules.integrations.model;

/**
 * Supported external service providers.
 *
 * <p>Persisted as plain text in {@code integration_connections.provider}
 * so a future provider (Linear, GitHub Enterprise, Discord, ...) can be
 * added without a schema migration. Each handler in
 * {@code com.livingdocs.modules.integrations.service.webhook} decides
 * whether it knows how to process the value.
 */
public enum IntegrationProvider {
    GITHUB,
    GITLAB,
    JIRA,
    SLACK
}