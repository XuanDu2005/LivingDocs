package com.livingdocs.common.oauth;

import com.livingdocs.modules.user.model.OAuthProvider;

/**
 * Standardised view of a user profile as returned by an OAuth provider.
 *
 * <p>Both Google and GitHub adapters translate their respective payloads
 * into this shape before handing off to {@code OAuthService}.
 */
public record OAuthUserInfo(
        OAuthProvider provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String displayName,
        String avatarUrl
) {
}
