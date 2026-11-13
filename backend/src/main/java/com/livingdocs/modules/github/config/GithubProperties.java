package com.livingdocs.modules.github.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for the GitHub integration.
 *
 * <p>Values are read from the {@code github.*} section of
 * {@code application.yml} and may be overridden by environment variables.
 * The {@code sandbox} flag controls whether the API client uses the
 * real GitHub REST API or a deterministic stub useful for local
 * development and CI runs without real credentials.
 */
@ConfigurationProperties(prefix = "github")
public class GithubProperties {

    private OAuth oauth = new OAuth();
    private Api api = new Api();
    private Webhook webhook = new Webhook();
    private boolean sandbox = true;

    public OAuth getOauth() { return oauth; }
    public void setOauth(OAuth oauth) { this.oauth = oauth; }

    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }

    public Webhook getWebhook() { return webhook; }
    public void setWebhook(Webhook webhook) { this.webhook = webhook; }

    public boolean isSandbox() { return sandbox; }
    public void setSandbox(boolean sandbox) { this.sandbox = sandbox; }

    public static class OAuth {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        /** Comma-separated list of OAuth scopes to request. */
        private String scopes = "repo,read:user,read:org";

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

        public String getScopes() { return scopes; }
        public void setScopes(String scopes) { this.scopes = scopes; }
    }

    public static class Api {
        private String baseUrl = "https://api.github.com";
        private int timeoutMs = 15_000;
        private String userAgent = "livingdocs-backend";
        /**
         * Maximum size (in bytes) of a GitHub response body that may be
         * buffered in memory by the reactive {@code WebClient}.
         *
         * <p>The Spring default is 256&nbsp;KB, which is far too small for
         * listing pull requests: a single page of 50 PRs for a busy
         * repository easily exceeds that and the request then fails with
         * {@code DataBufferLimitException} ("Exceeded limit on max bytes to
         * buffer : 262144"), which surfaces to the user as an error and
         * no data at all. 16&nbsp;MB leaves plenty of headroom while still
         * bounding the memory a single request can consume.
         */
        private int maxInMemoryBytes = 16 * 1024 * 1024;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

        public int getMaxInMemoryBytes() { return maxInMemoryBytes; }
        public void setMaxInMemoryBytes(int maxInMemoryBytes) { this.maxInMemoryBytes = maxInMemoryBytes; }
    }

    public static class Webhook {
        private String secret;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }
}