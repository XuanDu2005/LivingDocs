package com.livingdocs.modules.auth.controller;

import com.livingdocs.common.oauth.OAuthAdapterFactory;
import com.livingdocs.common.oauth.OAuthProperties;
import com.livingdocs.common.oauth.OAuthUserInfo;
import com.livingdocs.common.security.JwtService;
import com.livingdocs.modules.auth.model.OAuthState;
import com.livingdocs.modules.auth.service.OAuthService;
import com.livingdocs.modules.auth.service.OAuthStateService;
import com.livingdocs.modules.user.dto.UserResponse;
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
    private final String publicUrl;

    public OAuthController(OAuthStateService stateService,
                           OAuthService oauthService,
                           OAuthAdapterFactory adapterFactory,
                           OAuthProperties properties,
                           JwtService jwtService,
                           @Value("${app.public-url:http://localhost:8080}") String publicUrl) {
        this.stateService = stateService;
        this.oauthService = oauthService;
        this.adapterFactory = adapterFactory;
        this.properties = properties;
        this.jwtService = jwtService;
        this.publicUrl = publicUrl;
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

    @GetMapping("/{provider}/callback")
    @Operation(summary = "OAuth provider callback — resolves the user, issues a JWT, redirects to the front-end")
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
        if (state.getAction() == OAuthState.Action.LINK) {
            // LINK callbacks are handled by a separate, authenticated endpoint.
            return redirectWith(state.getRedirectAfter(), "link_callback_misrouted", null, null);
        }
        if (error != null) {
            return redirectToError(error);
        }
        try {
            OAuthUserInfo info = oauthService.exchangeAndFetch(p, code);
            User user = oauthService.resolveUser(info);
            JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getEmail());
            return redirectWithToken(state.getRedirectAfter(), issued.token(), p.wireValue(),
                    String.valueOf(issued.expiresAt().getEpochSecond()));
        } catch (Exception e) {
            log.error("OAuth callback failed for provider={}", provider, e);
            return redirectToError("exchange_failed");
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
                publicUrl,
                enc(token), enc(provider), enc(expEpoch));
        if (next != null && !next.isBlank()) {
            url += "&next=" + enc(next);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", url).build();
    }

    private ResponseEntity<Void> redirectWith(String next, String code,
                                              String provider, String message) {
        String url = String.format("%s/auth/callback?error=%s", publicUrl, enc(code));
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
