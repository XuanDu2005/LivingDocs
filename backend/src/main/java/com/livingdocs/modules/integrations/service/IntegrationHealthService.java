package com.livingdocs.modules.integrations.service;

import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.exception.GithubTokenInvalidException;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.repository.GithubConnectionRepository;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background scanner that detects GitHub OAuth tokens that have been
 * revoked, expired, or otherwise stopped working. When it finds one, it:
 *   - marks the connection as {@code EXPIRED};
 *   - sends the user a notification (in-app + email per their
 *     preferences) so they can reconnect.
 *
 * <p>Runs hourly to stay under GitHub's 5,000-requests-per-hour rate
 * limit on the /user endpoint (it costs one request per active
 * connection per run).
 */
@Service
public class IntegrationHealthService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationHealthService.class);

    private final GithubConnectionRepository connectionRepository;
    private final GithubOAuthService oauthService;
    private final GithubClient githubClient;
    private final NotificationService notificationService;

    public IntegrationHealthService(GithubConnectionRepository connectionRepository,
                                    GithubOAuthService oauthService,
                                    GithubClient githubClient,
                                    NotificationService notificationService) {
        this.connectionRepository = connectionRepository;
        this.oauthService = oauthService;
        this.githubClient = githubClient;
        this.notificationService = notificationService;
    }

    /**
     * Manually trigger a health check for the calling user. Returns
     * {@code true} if the token is healthy, {@code false} if it was
     * detected as invalid (in which case a notification has been
     * dispatched and the connection has been marked {@code EXPIRED}).
     */
    @Transactional
    public boolean checkCurrentUser() {
        var connOpt = connectionRepository.findByUserId(
                com.livingdocs.common.security.CurrentUser.requireId());
        if (connOpt.isEmpty()) return true;
        return checkOne(connOpt.get());
    }

    @Scheduled(fixedDelayString = "${app.integrations.github.health-check-interval-ms:3600000}",
               initialDelayString = "${app.integrations.github.health-check-initial-delay-ms:60000}")
    public void checkAll() {
        List<GithubConnection> active = connectionRepository.findAllByStatus(GithubConnection.Status.ACTIVE);
        log.debug("Integration health check: scanning {} active connection(s)", active.size());
        for (GithubConnection conn : active) {
            try {
                checkOne(conn);
            } catch (Exception e) {
                log.warn("Health check failed for user {}: {}", conn.getUserId(), e.getMessage());
            }
        }
    }

    private boolean checkOne(GithubConnection conn) {
        try {
            // Calling /user with a bad token returns 401; we use it as the
            // canonical "is this token still alive?" probe.
            githubClient.getCurrentUser(conn.getAccessToken());
            return true;
        } catch (GithubTokenInvalidException e) {
            log.info("Detected invalid GitHub token for user {}", conn.getUserId());
            oauthService.markConnectionStatus(conn.getUserId(), GithubConnection.Status.EXPIRED);
            notificationService.send(
                    conn.getUserId(),
                    "github.tokenInvalid",
                    "Your GitHub connection needs to be re-authorised",
                    "LivingDocs can no longer reach GitHub on your behalf. Please reconnect from the Workspaces page to keep ingesting pull requests and commits.",
                    "/workspaces"
            );
            return false;
        } catch (Exception other) {
            // Network blip — don't change the status, just log
            log.debug("Health check inconclusive for user {}: {}", conn.getUserId(), other.getMessage());
            return true;
        }
    }
}
