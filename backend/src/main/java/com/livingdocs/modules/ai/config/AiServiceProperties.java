package com.livingdocs.modules.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the AI service.
 */
@ConfigurationProperties(prefix = "ai-service")
public class AiServiceProperties {

    /** Base URL of the AI service, e.g. {@code http://ai-service:8000}. */
    private String baseUrl = "http://localhost:8000";
    private String apiPrefix = "/api/v1";

    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 90;

    /**
     * Whether the AI integration is enabled at all. When false, the backend
     * behaves as if no AI service were configured (every call returns null /
     * empty and the system stays usable with manual workflows).
     */
    private boolean enabled = true;

    /** Confidence threshold below which AI suggestions are flagged as low confidence. */
    private double confidenceThreshold = 0.65;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiPrefix() { return apiPrefix; }
    public void setApiPrefix(String apiPrefix) { this.apiPrefix = apiPrefix; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int v) { this.connectTimeoutSeconds = v; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int v) { this.readTimeoutSeconds = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double v) { this.confidenceThreshold = v; }
}