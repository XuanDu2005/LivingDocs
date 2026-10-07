package com.livingdocs.modules.auth.service;

import com.livingdocs.common.oauth.OAuthProperties;
import com.livingdocs.modules.auth.model.OAuthState;
import com.livingdocs.modules.auth.repository.OAuthStateRepository;
import com.livingdocs.modules.user.model.OAuthProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates the short-lived CSRF state used by the OAuth flow.
 *
 * <p>The state is a 32-byte cryptographically random value stored in the
 * {@code oauth_states} table along with the intended action (login /
 * register / link) and the (optional) user id for the LINK action.
 */
@Service
public class OAuthStateService {

    private static final SecureRandom RNG = new SecureRandom();

    private final OAuthStateRepository repository;
    private final int ttlMinutes;

    public OAuthStateService(OAuthStateRepository repository, OAuthProperties properties) {
        this.repository = repository;
        this.ttlMinutes = properties.getStateTtlMinutes();
    }

    @Transactional
    public OAuthState issue(OAuthState.Action action, OAuthProvider provider,
                            UUID userId, String redirectAfter) {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String key = HexFormat.of().formatHex(bytes);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(ttlMinutes);
        OAuthState state = new OAuthState(key, userId, action, provider, redirectAfter, expiresAt);
        return repository.save(state);
    }

    @Transactional
    public Optional<OAuthState> consume(String stateKey) {
        return repository.findById(stateKey)
                .filter(s -> !s.isExpired(OffsetDateTime.now()))
                .map(s -> {
                    repository.delete(s);
                    return s;
                });
    }
}
