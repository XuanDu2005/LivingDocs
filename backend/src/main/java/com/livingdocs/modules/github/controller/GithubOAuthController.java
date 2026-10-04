package com.livingdocs.modules.github.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.github.dto.GithubCallbackResponse;
import com.livingdocs.modules.github.dto.GithubConnectionResponse;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.service.GithubOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public GithubOAuthController(GithubOAuthService oauthService) {
        this.oauthService = oauthService;
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
    public ResponseEntity<GithubCallbackResponse> callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            @RequestParam(value = "expectedState", required = false) String expectedState) {
        GithubConnection connection = oauthService.completeCallback(code, state, expectedState);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GithubCallbackResponse.success(connection.getGithubLogin(), connection.getGithubUserId()));
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