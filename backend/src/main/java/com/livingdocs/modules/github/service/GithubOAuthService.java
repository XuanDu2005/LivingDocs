package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubTokenResponse;
import com.livingdocs.modules.github.client.GithubUser;
import com.livingdocs.modules.github.config.GithubProperties;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.repository.GithubConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the GitHub OAuth flow:
 * <ol>
 *   <li>Build the authorize URL with a CSRF {@code state} token.</li>
 *   <li>Exchange the authorization code for an access token.</li>
 *   <li>Persist the resulting {@link GithubConnection} bound to the
 *       current LivingDocs user.</li>
 * </ol>
 */
@Service
public class GithubOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GithubOAuthService.class);
    private static final String STATE_ATTRIBUTE = "github.oauth.state";

    private final GithubConnectionRepository connectionRepository;
    private final GithubClient githubClient;
    private final GithubProperties properties;
    private final SecureRandom random = new SecureRandom();

    public GithubOAuthService(GithubConnectionRepository connectionRepository,
                              GithubClient githubClient,
                              GithubProperties properties) {
        this.connectionRepository = connectionRepository;
        this.githubClient = githubClient;
        this.properties = properties;
    }

    /**
     * Builds the GitHub authorize URL the user should be redirected to.
     */
    public AuthorizeUrl buildAuthorizeUrl(UUID currentUserId) {
        String state = generateState();
        StringBuilder url = new StringBuilder("https://github.com/login/oauth/authorize")
                .append("?client_id=").append(urlEncode(properties.getOauth().getClientId()))
                .append("&redirect_uri=").append(urlEncode(properties.getOauth().getRedirectUri()))
                .append("&scope=").append(urlEncode(properties.getOauth().getScopes()))
                .append("&state=").append(urlEncode(state))
                .append("&allow_signup=true");
        return new AuthorizeUrl(url.toString(), state);
    }

    /**
     * Handles the OAuth callback. {@code expectedState} is the value
     * the server issued earlier; we compare it to {@code state} to
     * protect against CSRF.
     */
    @Transactional
    public GithubConnection completeCallback(String code, String state, String expectedState) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("OAuth callback missing 'code' parameter");
        }
        if (state == null || !state.equals(expectedState)) {
            throw new BadRequestException("OAuth state mismatch — possible CSRF");
        }
        UUID userId = CurrentUser.requireId();
        GithubTokenResponse token = githubClient.exchangeCode(code, state);
        if (token.isError()) {
            throw new BadRequestException("GitHub rejected the code: "
                    + token.error() + " — " + token.errorDescription());
        }
        GithubUser user = githubClient.getCurrentUser(token.accessToken());
        Optional<GithubConnection> existing = connectionRepository.findByUserId(userId);
        GithubConnection connection = existing.orElseGet(() ->
                GithubConnection.create(userId, user.id(), user.login(),
                        token.accessToken(), token.tokenType(), token.scope()));
        connection.setAccessToken(token.accessToken());
        connection.setScope(token.scope());
        connection.setStatus(GithubConnection.Status.ACTIVE);
        GithubConnection saved = connectionRepository.save(connection);
        log.info("GitHub connection established for user {} as {}", userId, user.login());
        return saved;
    }

    @Transactional(readOnly = true)
    public GithubConnection requireForCurrentUser() {
        UUID userId = CurrentUser.requireId();
        GithubConnection connection = connectionRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException(
                        "No GitHub connection for the current user. Authorize the app first."));
        if (connection.getStatus() != GithubConnection.Status.ACTIVE) {
            throw new com.livingdocs.common.exception.ForbiddenException(
                    "GitHub connection is " + connection.getStatus()
                            + ". Re-authorize the app to continue.");
        }
        return connection;
    }

    @Transactional(readOnly = true)
    public Optional<GithubConnection> findForCurrentUser() {
        UUID userId = CurrentUser.requireId();
        return connectionRepository.findByUserId(userId);
    }

    @Transactional
    public void disconnectCurrentUser() {
        UUID userId = CurrentUser.requireId();
        GithubConnection connection = connectionRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No GitHub connection to disconnect"));
        connection.setStatus(GithubConnection.Status.REVOKED);
        connectionRepository.save(connection);
        log.info("GitHub connection revoked for user {}", userId);
    }

    public void touch(GithubConnection connection) {
        connection.setLastUsedAt(OffsetDateTime.now());
        connectionRepository.save(connection);
    }

    /**
     * Used to disambiguate the case where a user is already connected
     * with a different GitHub account.
     */
    public void assertNoConflict(GithubConnection candidate) {
        connectionRepository.findByGithubUserId(candidate.getGithubUserId())
                .filter(existing -> !existing.getUserId().equals(candidate.getUserId()))
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This GitHub account is already linked to a different LivingDocs user");
                });
    }

    private String generateState() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record AuthorizeUrl(String url, String state) {}
}