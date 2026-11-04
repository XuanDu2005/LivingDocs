package com.livingdocs.modules.github.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.github.dto.GithubCallbackResponse;
import com.livingdocs.modules.github.dto.GithubConnectionResponse;
import com.livingdocs.modules.github.dto.GithubScopeResponse;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.github.service.GithubScopeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for managing the authenticated user's GitHub OAuth
 * connection.
 *
 * <p>The OAuth dance is split into three calls:
 * <ol>
 *   <li>{@code GET /github/oauth/authorize-url} — returns the GitHub
 *       authorize URL plus the {@code state} token the frontend must
 *       echo back in step 3.</li>
 *   <li>The browser is redirected to that URL; the user authorises
 *       LivingDocs on GitHub.</li>
 *   <li>{@code POST /github/oauth/callback} — backend exchanges the
 *       {@code code} for an access token, persists the connection,
 *       and returns the new identity.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/github")
@Tag(name = "GitHub", description = "GitHub OAuth connection management")
public class GithubOAuthController {

    private final GithubOAuthService oauthService;
    private final GithubScopeService scopeService;
    private final String frontendUrl;

    public GithubOAuthController(GithubOAuthService oauthService,
                                 GithubScopeService scopeService,
                                 @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.oauthService = oauthService;
        this.scopeService = scopeService;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/oauth/authorize-url")
    @Operation(summary = "Get the GitHub authorize URL for the current user")
    public ResponseEntity<AuthorizeUrlResponse> getAuthorizeUrl() {
        // requireId() ensures the caller is authenticated; we don't use
        // the userId in the URL itself, but validating it now gives a
        // nicer error than discovering it later in the callback.
        CurrentUser.requireId();
        GithubOAuthService.AuthorizeUrl authorize = oauthService.buildAuthorizeUrl(CurrentUser.requireId());
        return ResponseEntity.ok(new AuthorizeUrlResponse(authorize.url(), authorize.state()));
    }

    @GetMapping("/oauth/callback")
    @Operation(summary = "OAuth callback (browser redirect from GitHub)")
    public RedirectView callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        // Redirect to the frontend callback page, passing the code and state as query params.
        // The frontend will validate the state and complete the OAuth flow.
        String redirectUrl = frontendUrl + "/github/callback?code=" + code + "&state=" + state;
        return new RedirectView(redirectUrl);
    }

    /**
     * Internal endpoint for the frontend callback page to exchange the OAuth code.
     */
    @PostMapping("/oauth/complete")
    @Operation(summary = "Complete OAuth flow (called by frontend callback page)")
    public ResponseEntity<GithubCallbackResponse> complete(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            @RequestParam("expectedState") String expectedState) {
        GithubConnection connection = oauthService.completeCallback(code, state, expectedState);
        // Mirror the scopes GitHub actually granted into the reviewable
        // grant table, so the "review the scopes" screen reflects the
        // real token instead of the requested scope set.
        scopeService.syncFromToken(connection.getUserId(), connection.getScope());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GithubCallbackResponse.success(connection.getGithubLogin(), connection.getGithubUserId()));
    }

    // -----------------------------------------------------------------
    // Scope review
    //
    // The OAuth handshake asks GitHub for a fixed scope set. These
    // endpoints let the user see what that set actually grants and
    // re-consent with a narrower one.
    // -----------------------------------------------------------------

    @GetMapping("/scopes")
    @Operation(summary = "Review the repository access scopes granted to the GitHub connection")
    public ResponseEntity<GithubScopeService.ScopeOverview> scopes() {
        return ResponseEntity.ok(scopeService.overview());
    }

    @DeleteMapping("/scopes/{scope}")
    @Operation(summary = "Withdraw a granted repository access scope")
    public ResponseEntity<GithubScopeResponse> revokeScope(
            @PathVariable("scope") String scope,
            @RequestParam(required = false, defaultValue = "false") boolean force) {
        return ResponseEntity.ok(scopeService.revoke(scope, force));
    }

    /**
     * Get a GitHub authorize URL for a narrower scope set.
     *
     * <p>The {@code state} token is returned alongside the URL so the
     * frontend can echo it back on the callback. Dropping it made the
     * callback fail its CSRF check, because the backend compares the
     * state GitHub sends back with the one it issued.
     */
    @PostMapping("/scopes/reauthorize")
    @Operation(summary = "Get a GitHub authorize URL for a narrower scope set")
    public ResponseEntity<AuthorizeUrlResponse> reauthorize(@RequestBody List<String> scopes) {
        GithubOAuthService.AuthorizeUrl authorizeUrl = scopeService.authorizeUrlFor(scopes);
        return ResponseEntity.ok(new AuthorizeUrlResponse(authorizeUrl.url(), authorizeUrl.state()));
    }

    @GetMapping("/connection")
    @Operation(summary = "Get the current user's GitHub connection, if any")
    public ResponseEntity<GithubConnectionResponse> getConnection() {
        UUID userId = CurrentUser.requireId();
        return oauthService.findForCurrentUser()
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/connection")
    @Operation(summary = "Disconnect the current user's GitHub account")
    public ResponseEntity<Void> disconnect() {
        oauthService.disconnectCurrentUser();
        return ResponseEntity.noContent().build();
    }

    private GithubConnectionResponse toResponse(GithubConnection c) {
        return new GithubConnectionResponse(
                c.getId().toString(),
                c.getGithubLogin(),
                c.getGithubUserId(),
                c.getScope(),
                c.getStatus().name(),
                c.getConnectedAt(),
                c.getLastUsedAt(),
                c.getLastSyncedAt());
    }

    public record AuthorizeUrlResponse(String url, String state) {}
}