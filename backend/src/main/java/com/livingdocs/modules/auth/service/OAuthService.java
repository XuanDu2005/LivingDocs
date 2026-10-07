package com.livingdocs.modules.auth.service;

import com.livingdocs.common.email.EmailService;
import com.livingdocs.common.email.EmailTemplateService;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.oauth.OAuthAdapterFactory;
import com.livingdocs.common.oauth.OAuthUserInfo;
import com.livingdocs.modules.user.model.OAuthProvider;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.model.UserOAuthIdentity;
import com.livingdocs.modules.user.repository.UserOAuthIdentityRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Bridges {@link OAuthUserInfo} (a normalised provider payload) with the
 * local user database.
 *
 * <p>Three outcomes are possible when handling a successful OAuth round-trip:
 * <ol>
 *   <li>The (provider, providerUserId) tuple is already linked to a user
 *       → just issue a JWT for that user.</li>
 *   <li>It is not linked, but the provider's email matches an existing
 *       unverified local account → auto-link and mark the email as verified.</li>
 *   <li>Otherwise create a fresh user (with the provider's verified email)
 *       and link the identity.</li>
 * </ol>
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

    private final UserRepository userRepository;
    private final UserOAuthIdentityRepository identityRepository;
    private final OAuthAdapterFactory adapterFactory;
    private final EmailService emailService;

    public OAuthService(UserRepository userRepository,
                        UserOAuthIdentityRepository identityRepository,
                        OAuthAdapterFactory adapterFactory,
                        EmailService emailService) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.adapterFactory = adapterFactory;
        this.emailService = emailService;
    }

    /**
     * Resolve the user behind the given OAuth payload. Creates a new user
     * if needed.
     */
    @Transactional
    public User resolveUser(OAuthUserInfo info) {
        // 1) Already linked?
        Optional<UserOAuthIdentity> existing = identityRepository
                .findByProviderAndProviderUserId(info.provider(), info.providerUserId());
        if (existing.isPresent()) {
            UserOAuthIdentity id = existing.get();
            id.touchLastUsed();
            identityRepository.save(id);
            return userRepository.findById(id.getUserId())
                    .orElseThrow(() -> new ConflictException(
                            "Linked OAuth identity points to a missing user"));
        }

        // 2) Email matches a local account?
        String normalizedEmail = info.email() == null ? null : info.email().toLowerCase(Locale.ROOT);
        Optional<User> byEmail = normalizedEmail == null
                ? Optional.empty()
                : userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            linkIdentity(user.getId(), info);
            if (info.emailVerified() && !user.isEmailVerified()) {
                user.markEmailVerified();
                userRepository.save(user);
            }
            log.info("Linked {} identity to existing user {}", info.provider(), user.getEmail());
            return user;
        }

        // 3) Brand-new user.
        if (!info.emailVerified()) {
            throw new ConflictException(
                    "Cannot create an account from an unverified provider email");
        }
        User fresh = userRepository.save(new User(
                normalizedEmail,
                info.displayName() == null ? normalizedEmail : info.displayName(),
                "!" + UUID.randomUUID() + "!"   // random placeholder — OAuth users have no password
        ));
        fresh.markEmailVerified();
        userRepository.save(fresh);
        linkIdentity(fresh.getId(), info);
        sendWelcomeSafely(fresh.getEmail(), fresh.getDisplayName());
        log.info("Created new user {} via {}", fresh.getEmail(), info.provider());
        return fresh;
    }

    /**
     * Best-effort welcome email. Failures (SMTP unavailable in dev, etc.)
     * must not block the OAuth flow.
     */
    private void sendWelcomeSafely(String email, String name) {
        try {
            emailService.send(EmailTemplateService.welcome(email, name));
        } catch (Exception e) {
            log.warn("Failed to send welcome email to {}: {}", email, e.toString());
        }
    }

    /**
     * Manually link an OAuth identity to the currently authenticated user.
     *
     * @throws ConflictException if the identity is already linked to
     *                           a different account
     */
    @Transactional
    public UserOAuthIdentity linkIdentityToCurrentUser(UUID userId, OAuthUserInfo info) {
        Optional<UserOAuthIdentity> clash = identityRepository
                .findByProviderAndProviderUserId(info.provider(), info.providerUserId());
        if (clash.isPresent() && !clash.get().getUserId().equals(userId)) {
            throw new ConflictException("This " + info.provider().wireValue()
                    + " account is already linked to a different LivingDocs user");
        }
        if (clash.isPresent()) {
            return clash.get();
        }
        return linkIdentity(userId, info);
    }

    /**
     * Unlink the given provider from the given user. Returns the number of
     * rows actually removed (0 if nothing was linked).
     */
    @Transactional
    public int unlink(UUID userId, OAuthProvider provider) {
        return identityRepository.findByUserIdAndProvider(userId, provider)
                .map(id -> {
                    identityRepository.delete(id);
                    return 1;
                })
                .orElse(0);
    }

    private UserOAuthIdentity linkIdentity(UUID userId, OAuthUserInfo info) {
        UserOAuthIdentity identity = new UserOAuthIdentity(
                userId, info.provider(), info.providerUserId(),
                info.email(), info.displayName(), info.avatarUrl());
        return identityRepository.save(identity);
    }

    public OAuthUserInfo exchangeAndFetch(OAuthProvider provider, String code) {
        return adapterFactory.get(provider).exchangeAndFetchUser(code);
    }
}
