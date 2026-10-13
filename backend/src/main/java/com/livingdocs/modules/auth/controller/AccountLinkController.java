package com.livingdocs.modules.auth.controller;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.oauth.OAuthAdapterFactory;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.auth.dto.LinkedProvidersResponse;
import com.livingdocs.modules.auth.model.OAuthState;
import com.livingdocs.modules.auth.service.OAuthService;
import com.livingdocs.modules.auth.service.OAuthStateService;
import com.livingdocs.modules.user.model.OAuthProvider;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.model.UserOAuthIdentity;
import com.livingdocs.modules.user.repository.UserOAuthIdentityRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated endpoints for managing OAuth providers linked to the
 * currently signed-in user.
 *
 * <p>The flow (single callback URL shared with the sign-in flow):
 * <ol>
 *   <li>{@code POST /oauth/{provider}/link/start} — must be authenticated
 *       (the JWT proves the user is signed in). Returns the provider's
 *       authorize URL with a CSRF state whose {@code action = LINK}.</li>
 *   <li>The provider 302s the browser back to
 *       {@code GET /oauth/{provider}/callback} (the same endpoint used
 *       for sign-in). The callback inspects {@link OAuthState#getAction()}
 *       and, for {@code LINK}, uses the JWT principal from the request
 *       to force-link the identity to the current user.</li>
 *   <li>Success → {@code /auth/callback?linked=...}. Failure →
 *       {@code /auth/callback?error=...}.</li>
 * </ol>
 *
 * <p>Note: the callback URL must match what is registered with the
 * provider (i.e. {@code app.oauth.google.redirect-uri}), so a single
 * redirect URI is reused for both flows.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "Auth (OAuth)", description = "Manage linked providers")
public class AccountLinkController {

    private static final Logger log = LoggerFactory.getLogger(AccountLinkController.class);

    private final OAuthStateService stateService;
    private final OAuthService oauthService;
    private final OAuthAdapterFactory adapterFactory;
    private final UserRepository userRepository;
    private final UserOAuthIdentityRepository identityRepository;

    public AccountLinkController(OAuthStateService stateService,
                                  OAuthService oauthService,
                                  OAuthAdapterFactory adapterFactory,
                                  UserRepository userRepository,
                                  UserOAuthIdentityRepository identityRepository) {
        this.stateService = stateService;
        this.oauthService = oauthService;
        this.adapterFactory = adapterFactory;
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
    }

    @GetMapping("/providers")
    @Operation(summary = "List the OAuth providers currently linked to the signed-in user")
    public ResponseEntity<LinkedProvidersResponse> listProviders() {
        UUID userId = CurrentUser.requireId();
        List<LinkedProvidersResponse.LinkedProvider> linked = identityRepository
                .findAllByUserId(userId).stream()
                .map(i -> new LinkedProvidersResponse.LinkedProvider(
                        i.getProvider().wireValue(),
                        i.getProviderEmail(),
                        i.getDisplayName(),
                        i.getAvatarUrl()))
                .toList();
        return ResponseEntity.ok(new LinkedProvidersResponse(linked));
    }

    @PostMapping("/{provider}/link/start")
    @Operation(summary = "Start the link flow — returns the provider authorize URL")
    public ResponseEntity<Map<String, String>> startLink(@PathVariable String provider) {
        UUID userId = CurrentUser.requireId();
        OAuthProvider p = OAuthProvider.fromWire(provider);
        OAuthState state = stateService.issue(OAuthState.Action.LINK, p, userId, null);
        String url = adapterFactory.get(p).buildAuthorizationUrl(state.getStateKey());
        // Return 200 OK with the URL in the body. The front-end performs
        // a top-level navigation (window.location.href = url) to the
        // provider, so we don't need to send a 302 here.
        return ResponseEntity.ok(Map.of("url", url, "state", state.getStateKey()));
    }

    @PostMapping("/{provider}/link/confirm-merge")
    @Operation(summary = "Confirm merging the OAuth identity into the current user account")
    public ResponseEntity<LinkedProvidersResponse.LinkedProvider> confirmMerge(
            @PathVariable String provider,
            @RequestParam("providerUserId") String providerUserId,
            @RequestParam("providerEmail") String providerEmail,
            @RequestParam(value = "displayName", required = false) String displayName) {
        UUID userId = CurrentUser.requireId();
        OAuthProvider p = OAuthProvider.fromWire(provider);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        // Pre-conditions:
        //  * The identity is NOT already linked to anyone.
        //  * The current user does not already have this provider linked.
        identityRepository.findByProviderAndProviderUserId(p, providerUserId)
                .ifPresent(existing -> {
                    if (!existing.getUserId().equals(userId)) {
                        throw new ConflictException("Identity already linked to a different account");
                    }
                });
        if (identityRepository.existsByUserIdAndProvider(userId, p)) {
            throw new ConflictException("Provider already linked to this account");
        }

        UserOAuthIdentity identity = new UserOAuthIdentity(
                userId, p, providerUserId,
                providerEmail, displayName == null ? user.getDisplayName() : displayName, null);
        identityRepository.save(identity);
        log.info("Merged {} identity into user {} via confirm", p, userId);

        return ResponseEntity.ok(new LinkedProvidersResponse.LinkedProvider(
                p.wireValue(), providerEmail, displayName, null));
    }

    @DeleteMapping("/{provider}/link")
    @Operation(summary = "Unlink the given provider from the signed-in user")
    public ResponseEntity<Void> unlink(@PathVariable String provider) {
        UUID userId = CurrentUser.requireId();
        OAuthProvider p = OAuthProvider.fromWire(provider);
        int removed = oauthService.unlink(userId, p);
        if (removed == 0) {
            throw new NotFoundException("No " + p.wireValue() + " identity to unlink");
        }
        return ResponseEntity.noContent().build();
    }
}
