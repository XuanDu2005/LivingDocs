package com.livingdocs.common.oauth;

import com.livingdocs.common.exception.OAuthExchangeException;
import com.livingdocs.modules.user.model.OAuthProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic offline OAuth adapter used when {@code app.oauth.<provider>.sandbox=true}.
 *
 * <p>It does NOT talk to Google or GitHub. Instead, it accepts a fake
 * authorization code of the form {@code sandbox:<providerUserId>:<email>}
 * and returns a synthetic profile. Useful for local development without
 * registered OAuth clients.
 */
public class SandboxOAuthAdapter implements OAuthProviderAdapter {

    private final OAuthProvider provider;
    private final Map<String, OAuthUserInfo> known = new ConcurrentHashMap<>();

    public SandboxOAuthAdapter(OAuthProvider provider) {
        this.provider = provider;
    }

    @Override
    public OAuthProvider provider() {
        return provider;
    }

    @Override
    public String buildAuthorizationUrl(String state) {
        // In sandbox mode the front-end detects this URL and short-circuits
        // straight to the local /callback with a fake code.
        return "/api/v1/auth/oauth/" + provider.wireValue() + "/sandbox-start?state=" + state;
    }

    @Override
    public OAuthUserInfo exchangeAndFetchUser(String code) {
        // Code format: "sandbox:<id>:<email>"
        String[] parts = code.split(":", 3);
        if (parts.length != 3 || !"sandbox".equals(parts[0])) {
            throw new OAuthExchangeException("Invalid sandbox code");
        }
        return new OAuthUserInfo(
                provider,
                parts[1],
                parts[2],
                true,
                "Sandbox " + provider + " user",
                null);
    }
}
