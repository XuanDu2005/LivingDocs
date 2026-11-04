package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.github.dto.GithubScopeResponse;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.GithubScopeGrant;
import com.livingdocs.modules.github.repository.GithubScopeGrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The reviewable record of the repository access scopes the user granted
 * to the LivingDocs GitHub application.
 *
 * <p>The OAuth handshake itself asks GitHub for a fixed scope set (see
 * {@code github.oauth.scopes}). What the user can actually <em>review</em>
 * is what this service exposes: the list of scopes in force, a
 * human-readable description of each, and which of them the platform
 * actually needs for each feature — so the screen can say "workflow is
 * only needed for the CI template feature, which you have not enabled".
 *
 * <p>Changing a grant means re-authorizing with a different scope set;
 * GitHub does not let an OAuth token shed a scope unilaterally. This
 * service therefore records the intent and produces the authorize URL
 * for the narrower set.
 */
@Service
public class GithubScopeService {

    private static final Logger log = LoggerFactory.getLogger(GithubScopeService.class);

    /**
     * The scopes the platform understands, with the plain-language
     * description and the feature that needs them.
     */
    private static final List<ScopeDefinition> KNOWN_SCOPES = List.of(
            new ScopeDefinition("repo",
                    "Read and write access to your private repositories",
                    "Sync source code, read pull requests and publish documentation back.",
                    false),
            new ScopeDefinition("public_repo",
                    "Read and write access to your public repositories",
                    "Only needed for public repositories; the private-repo scope already covers them.",
                    false),
            new ScopeDefinition("read:user",
                    "Read your public profile",
                    "Links your GitHub account to your LivingDocs account.",
                    false),
            new ScopeDefinition("read:org",
                    "Read your organization membership",
                    "Lets you pick repositories of the organizations you belong to.",
                    false),
            new ScopeDefinition("read:main",
                    "Read the contents of a repository",
                    "The minimum scope needed to read source code.",
                    false),
            new ScopeDefinition("workflow",
                    "Add or update workflow files",
                    "Only needed if LivingDocs should write CI workflow definitions.",
                    false),
            new ScopeDefinition("admin:org",
                    "Full control of your organization membership",
                    "Only needed if LivingDocs should manage organization repositories.",
                    false));

    private final GithubScopeGrantRepository grantRepository;
    private final GithubOAuthService oauthService;
    private final PermissionService permissionService;

    public GithubScopeService(GithubScopeGrantRepository grantRepository,
                              GithubOAuthService oauthService,
                              PermissionService permissionService) {
        this.grantRepository = grantRepository;
        this.oauthService = oauthService;
        this.permissionService = permissionService;
    }

    /**
     * The scopes in force for the current user, plus the scopes the
     * platform knows about, so the review screen can show both what is
     * granted and what could be granted.
     */
    @Transactional(readOnly = true)
    public ScopeOverview overview() {
        UUID userId = CurrentUser.requireId();
        permissionService.requireAnyPermission(userId, PermissionCodes.GITHUB_SCOPE_VIEW);

        GithubConnection connection = oauthService.findForCurrentUser().orElse(null);
        List<GithubScopeGrant> grants = grantRepository.findByUserIdOrderByScopeAsc(userId);

        Set<String> activeScopes = new LinkedHashSet<>();
        for (GithubScopeGrant grant : grants) {
            if (grant.isActive()) {
                activeScopes.add(grant.getScope());
            }
        }
        // The token's own scope string is the source of truth when it is
        // available; the grant table is the reviewable projection of it.
        if (connection != null && connection.getScope() != null) {
            for (String scope : connection.getScope().split(",")) {
                if (!scope.isBlank()) {
                    activeScopes.add(scope.trim());
                }
            }
        }

        List<ScopeDefinition> available = new ArrayList<>();
        for (ScopeDefinition def : KNOWN_SCOPES) {
            available.add(new ScopeDefinition(
                    def.scope(),
                    def.description(),
                    def.usage(),
                    activeScopes.contains(def.scope())));
        }
        // Scopes GitHub returned that the platform does not know about
        // are still shown — the user granted them, so hiding them would
        // make the review screen incomplete.
        for (String scope : activeScopes) {
            boolean known = available.stream().anyMatch(d -> d.scope().equals(scope));
            if (!known) {
                available.add(new ScopeDefinition(scope,
                        "GitHub scope '" + scope + "'", "Granted outside LivingDocs.", true));
            }
        }

        return new ScopeOverview(
                connection == null ? null : connection.getGithubLogin(),
                connection == null ? null : connection.getStatus().name(),
                available,
                grants.stream().map(GithubScopeResponse::from).toList());
    }

    /**
     * Record that the user granted a scope.
     *
     * <p>Called after a successful OAuth round-trip: GitHub reports the
     * scopes it actually granted, and they are mirrored into the grant
     * table so the review screen reflects reality.
     */
    @Transactional
    public void syncFromToken(UUID userId, String scopeString) {
        if (scopeString == null || scopeString.isBlank()) {
            return;
        }
        for (String raw : scopeString.split(",")) {
            String scope = raw.trim();
            if (scope.isEmpty()) {
                continue;
            }
            var existing = grantRepository.findByUserIdAndScope(userId, scope);
            if (existing.isPresent()) {
                existing.get().grant();
                existing.get().setDescription(describe(scope));
                grantRepository.save(existing.get());
            } else {
                grantRepository.save(new GithubScopeGrant(userId, scope, describe(scope)));
            }
        }
        log.info("Synced {} GitHub scope grant(s) for user {}", scopeString, userId);
    }

    /**
     * Withdraw a scope grant.
     *
     * <p>The grant is marked revoked rather than deleted, so the history
     * stays visible. A scope the platform needs (one of the scopes the
     * OAuth flow requested) cannot be withdrawn without re-authorizing,
     * so the caller must pass {@code force} to confirm.
     */
    @Transactional
    public GithubScopeResponse revoke(String scope, boolean force) {
        UUID userId = CurrentUser.requireId();
        permissionService.requireAnyPermission(userId, PermissionCodes.GITHUB_SCOPE_MANAGE);
        GithubScopeGrant grant = grantRepository.findByUserIdAndScope(userId, scope)
                .orElseThrow(() -> new NotFoundException("Scope grant not found"));
        if (isRequired(scope) && !force) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "Scope '" + scope + "' is required for the features you are using. "
                            + "Revoke it with force=true to withdraw it anyway.");
        }
        grant.revoke();
        GithubScopeGrant saved = grantRepository.save(grant);
        log.info("GitHub scope {} revoked for user {}", scope, userId);
        return GithubScopeResponse.from(saved);
    }

    /**
     * The authorize URL for a narrower scope set, so the user can
     * actually re-consent with fewer scopes.
     *
     * <p>The CSRF {@code state} travels with the URL: the frontend
     * echoes it back on the callback, and the backend compares the two.
     */
    @Transactional(readOnly = true)
    public GithubOAuthService.AuthorizeUrl authorizeUrlFor(List<String> scopes) {
        UUID userId = CurrentUser.requireId();
        permissionService.requireAnyPermission(userId, PermissionCodes.GITHUB_SCOPE_MANAGE);
        if (scopes == null || scopes.isEmpty()) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "At least one scope is required");
        }
        Set<String> invalid = new LinkedHashSet<>(scopes);
        invalid.removeAll(KNOWN_SCOPES.stream().map(ScopeDefinition::scope).toList());
        if (!invalid.isEmpty()) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "Unknown scope(s): " + String.join(", ", invalid));
        }
        String scopeParam = String.join(",", scopes);
        return oauthService.buildAuthorizeUrl(userId, scopeParam);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private boolean isRequired(String scope) {
        return switch (scope) {
            case "repo", "read:user" -> true;
            default -> false;
        };
    }

    private String describe(String scope) {
        return KNOWN_SCOPES.stream()
                .filter(d -> d.scope().equals(scope))
                .map(ScopeDefinition::description)
                .findFirst()
                .orElse("GitHub scope '" + scope + "'");
    }

    /**
     * One known scope, with the feature that needs it.
     */
    public record ScopeDefinition(
            String scope,
            String description,
            String usage,
            boolean granted
    ) {
    }

    /**
     * The full scope review payload.
     */
    public record ScopeOverview(
            String githubLogin,
            String connectionStatus,
            List<ScopeDefinition> available,
            List<GithubScopeResponse> grants
    ) {
    }

    /** Convenience for tests and callers that only need the names. */
    public static List<String> knownScopeNames() {
        return Arrays.stream(KNOWN_SCOPES.toArray(new ScopeDefinition[0]))
                .map(ScopeDefinition::scope)
                .toList();
    }
}
