package com.livingdocs.common.oauth;

import com.livingdocs.modules.user.model.OAuthProvider;

/**
 * Contract every OAuth provider adapter must implement.
 *
 * <p>One adapter is registered per provider value. The factory bean
 * resolves the right adapter by name at runtime.
 */
public interface OAuthProviderAdapter {

    /** The provider this adapter speaks to. */
    OAuthProvider provider();

    /** URL the browser is redirected to in order to start the flow. */
    String buildAuthorizationUrl(String state);

    /**
     * Exchange the authorization code for an access token, then call the
     * provider's user-info endpoint and return the standard user shape.
     */
    OAuthUserInfo exchangeAndFetchUser(String code);
}
