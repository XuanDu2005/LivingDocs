package com.livingdocs.common.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the OAuth 2.0 authorization-code flow.
 *
 * <p>Both Google and GitHub are configured independently. Leave the
 * client-id empty to disable a provider; the OAuthController will
 * respond with 503 in that case.
 */
@ConfigurationProperties(prefix = "app.oauth")
public class OAuthProperties {

    private Provider google = new Provider();
    private Provider github = new Provider();
    private int stateTtlMinutes = 10;

    public Provider getGoogle() { return google; }
    public void setGoogle(Provider google) { this.google = google; }

    public Provider getGithub() { return github; }
    public void setGithub(Provider github) { this.github = github; }

    public int getStateTtlMinutes() { return stateTtlMinutes; }
    public void setStateTtlMinutes(int stateTtlMinutes) { this.stateTtlMinutes = stateTtlMinutes; }

    public static class Provider {
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";
        private List<String> scopes = List.of();
        /** Sandbox mode returns deterministic fake data — useful in dev. */
        private boolean sandbox = false;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
        public boolean isSandbox() { return sandbox; }
        public void setSandbox(boolean sandbox) { this.sandbox = sandbox; }

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank();
        }
    }
}
