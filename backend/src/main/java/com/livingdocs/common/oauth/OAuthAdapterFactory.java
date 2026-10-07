package com.livingdocs.common.oauth;

import com.livingdocs.modules.user.model.OAuthProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves the right {@link OAuthProviderAdapter} for a given
 * {@link OAuthProvider}, taking the {@code sandbox} flag into account.
 *
 * <p>Sandbox adapters take priority when enabled so dev environments do
 * not need real Google/GitHub credentials.
 */
@Component
public class OAuthAdapterFactory {

    private final Map<OAuthProvider, OAuthProviderAdapter> adapters = new EnumMap<>(OAuthProvider.class);

    public OAuthAdapterFactory(OAuthProperties properties,
                               GoogleOAuthAdapter google,
                               GithubOAuthAdapter github) {
        OAuthProperties.Provider gCfg = properties.getGoogle();
        OAuthProperties.Provider ghCfg = properties.getGithub();
        adapters.put(OAuthProvider.GOOGLE, gCfg.isSandbox() || !gCfg.isConfigured()
                ? new SandboxOAuthAdapter(OAuthProvider.GOOGLE) : google);
        adapters.put(OAuthProvider.GITHUB, ghCfg.isSandbox() || !ghCfg.isConfigured()
                ? new SandboxOAuthAdapter(OAuthProvider.GITHUB) : github);
    }

    public OAuthProviderAdapter get(OAuthProvider provider) {
        OAuthProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new IllegalStateException("No adapter for " + provider);
        }
        return adapter;
    }
}
