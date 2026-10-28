package com.livingdocs.modules.auth.controller;

import com.livingdocs.common.oauth.OAuthAdapterFactory;
import com.livingdocs.common.oauth.OAuthProperties;
import com.livingdocs.common.oauth.OAuthUserInfo;
import com.livingdocs.common.security.JwtService;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.auth.model.OAuthState;
import com.livingdocs.modules.auth.service.OAuthService;
import com.livingdocs.modules.auth.service.OAuthStateService;
import com.livingdocs.modules.user.model.OAuthProvider;
import com.livingdocs.modules.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints for the OAuth authorization-code flow.
 *
 * <p>{@code GET /start}  → 302 to the provider's authorize URL with a
 * freshly-issued CSRF state.
 * <p>{@code GET /callback} → resolves the user (existing / auto-link /
 * new), issues a JWT, and 302s the browser back to the front-end's
 * callback page with the token in the query string.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "Auth (OAuth)", description = "Sign in or register with Google / GitHub")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthStateService stateService;
    private final OAuthService oauthService;
    private final OAuthAdapterFactory adapterFactory;
    private final OAuthProperties properties;
    private final JwtService jwtService;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final String frontendUrl;

    public OAuthController(OAuthStateService stateService,
                           OAuthService oauthService,
                           OAuthAdapterFactory adapterFactory,
                           OAuthProperties properties,
                           JwtService jwtService,
                           UserRoleAssignmentRepository roleAssignmentRepository,
                           @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.stateService = stateService;
        this.oauthService = oauthService;
        this.adapterFactory = adapterFactory;
        this.properties = properties;
        this.jwtService = jwtService;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/{provider}/start")
    @Operation(summary = "Start the OAuth flow — 302 to provider authorize URL")
    public ResponseEntity<Void> start(@PathVariable String provider,
                                      @RequestParam(name = "action", defaultValue = "LOGIN") String action,
                                      @RequestParam(name = "next", required = false) String next) {
        OAuthProvider p = OAuthProvider.fromWire(provider);
        OAuthState.Action act = parseAction(action);
        OAuthState state = stateService.issue(act, p, null, next);
        String url = adapterFactory.get(p).buildAuthorizationUrl(state.getStateKey());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", url)
                .build();
    }

    /**
     * Sandbox-only shortcut. When {@code app.oauth.<provider>.sandbox=true} (or the
     * provider has no client id), {@link com.livingdocs.common.oauth.SandboxOAuthAdapter}
     * builds an authorization URL that points here. We mint a deterministic
     * "code" the sandbox adapter understands and forward the browser to the
     * same {@code /callback} endpoint the real provider would have hit.
     *
     * <p>Dev-only convenience, gated by {@code sandbox} flag.
     */
    @GetMapping("/{provider}/sandbox-start")
    @Operation(summary = "Sandbox-only OAuth short-circuit — only available in dev")
    public ResponseEntity<Void> sandboxStart(@PathVariable String provider,
                                             @RequestParam("state") String stateKey) {
        OAuthProvider p = OAuthProvider.fromWire(provider);
        if (!adapterFactory.get(p).getClass().getSimpleName().equals("sandboxOAuthAdapter")
                && !adapterFactory.get(p).getClass().getSimpleName().equals("SandboxOAuthAdapter")) {
            log.warn("sandbox-start called for non-sandbox provider={}", provider);
            return redirectToError("sandbox_disabled");
        }
        String sandboxCode = "sandbox:" + UUID.randomUUID() + ":sandbox-" + p.wireValue()
                + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        String url = String.format("/api/v1/auth/oauth/%s/callback?code=%s&state=%s",
                p.wireValue(), enc(sandboxCode), enc(stateKey));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", url)
                .build();
    }

    /**
     * OAuth provider callback. This endpoint handles BOTH the
     * sign-in / register flow AND the "link provider to existing
     * account" flow. The difference is stored in {@link OAuthState#getAction()}.
     *
     * <ul>
     *   <li>{@code LOGIN} / {@code REGISTER} — issue a JWT and 302 back
     *       to the front-end with the token in the query string.</li>
     *   <li>{@code LINK} — the user must already be authenticated
     *       (carrying a valid JWT). The identity returned by the
     *       provider is force-linked to the current user, then the
     *       browser is 302-ed back to the profile page with
     *       {@code ?linked=...}.</li>
     * </ul>
     */
    @GetMapping("/{provider}/callback")
    @Operation(summary = "OAuth provider callback — resolves the user (login/link), issues a JWT or links the identity, redirects to the front-end")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam("code") String code,
                                         @RequestParam("state") String stateKey,
                                         @RequestParam(name = "error", required = false) String error) {
        OAuthProvider p = OAuthProvider.fromWire(provider);
        var stateOpt = stateService.consume(stateKey);
        if (stateOpt.isEmpty()) {
            log.warn("OAuth callback with unknown/expired state_key={}", stateKey);
            return redirectToError("invalid_state");
        }
        OAuthState state = stateOpt.get();
        if (error != null) {
            return redirectToError(error);
        }
        try {
            OAuthUserInfo info = oauthService.exchangeAndFetch(p, code);

            if (state.getAction() == OAuthState.Action.LINK) {
                return handleLinkCallback(state, p, info);
            }

            // LOGIN / REGISTER
            User user = oauthService.resolveUser(info);
            List<String> roleCodes = roleAssignmentRepository.findActiveRoleCodesByUserId(user.getId());
            JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getEmail(), roleCodes);
            return redirectWithToken(state.getRedirectAfter(), issued.token(), p.wireValue(),
                    String.valueOf(issued.expiresAt().getEpochSecond()));
        } catch (Exception e) {
            log.error("OAuth callback failed for provider={} action={}", provider, state.getAction(), e);
            return redirectToError("exchange_failed");
        }
    }

    /**
     * Handle the LINK branch of the callback. The user id is read from
     * the consumed {@link OAuthState} (it was bound to the state at
     * {@code /link/start} time) rather than from the request's JWT
     * principal, because top-level browser navigations through
     * Google/GitHub do not carry the {@code Authorization} header.
     * The identity is force-linked to that user, then the browser is
     * redirected to the profile page with {@code ?linked=...}.
     */
    private ResponseEntity<Void> handleLinkCallback(OAuthState state, OAuthProvider p, OAuthUserInfo info) {
        UUID linkUserId = state.getUserId();
        if (linkUserId == null) {
            // Should never happen — the LINK state always carries a userId,
            // but we guard against a corrupt/legacy state row.
            log.error("LINK callback state is missing userId (provider={})", p);
            return redirectToError("link_session_missing");
        }
        try {
            oauthService.linkIdentityToCurrentUserForce(linkUserId, info);
            log.info("Linked {} identity to user {} via /callback", p, linkUserId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", frontendUrl + "/auth/callback?linked=" + enc(p.wireValue()))
                    .build();
        } catch (Exception e) {
            log.error("Link callback failed for {}", p, e);
            return redirectToError("link_failed");
        }
    }

    // ----- helpers --------------------------------------------------------

    private static OAuthState.Action parseAction(String raw) {
        try {
            return OAuthState.Action.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return OAuthState.Action.LOGIN;
        }
    }

    private ResponseEntity<Void> redirectWithToken(String next, String token,
                                                   String provider, String expEpoch) {
        String url = String.format(
                "%s/auth/callback?token=%s&provider=%s&expiresAt=%s",
                frontendUrl,
                enc(token), enc(provider), enc(expEpoch));
        if (next != null && !next.isBlank()) {
            url += "&next=" + enc(next);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", url).build();
    }

    private ResponseEntity<Void> redirectWith(String next, String code,
                                              String provider, String message) {
        String url = String.format("%s/auth/callback?error=%s", frontendUrl, enc(code));
        if (provider != null) url += "&provider=" + enc(provider);
        if (message != null) url += "&message=" + enc(message);
        if (next != null && !next.isBlank()) url += "&next=" + enc(next);
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", url).build();
    }

    private ResponseEntity<Void> redirectToError(String code) {
        return redirectWith(null, code, null, null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
