package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubRepositorySummary;
import com.livingdocs.modules.github.config.GithubProperties;
import com.livingdocs.modules.github.dto.GithubRepositoryCatalogItem;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages repositories that have been linked to a workspace.
 *
 * <p>Connecting a repository is a two-step process: the user first
 * browses the GitHub catalog (returned by
 * {@link GithubClient#listUserRepositories(String, int)}) and then
 * calls {@link #connect(UUID, Long, String)} to attach one of those
 * repositories to a workspace.
 */
@Service
public class GithubRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(GithubRepositoryService.class);

    private final RepositoryRepository repositoryRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final GithubOAuthService oauthService;
    private final GithubClient githubClient;
    private final GithubProperties githubProperties;
    private final String appPublicUrl;

    public GithubRepositoryService(RepositoryRepository repositoryRepository,
                                   WorkspaceRepository workspaceRepository,
                                   WorkspaceMemberRepository workspaceMemberRepository,
                                   GithubOAuthService oauthService,
                                   GithubClient githubClient,
                                   GithubProperties githubProperties,
                                   @Value("${app.public-url:http://localhost:8080}") String appPublicUrl) {
        this.repositoryRepository = repositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.oauthService = oauthService;
        this.githubClient = githubClient;
        this.githubProperties = githubProperties;
        this.appPublicUrl = appPublicUrl.endsWith("/")
                ? appPublicUrl.substring(0, appPublicUrl.length() - 1)
                : appPublicUrl;
    }

    @Transactional(readOnly = true)
    public List<GithubRepositoryCatalogItem> listCatalog() {
        GithubConnection conn = oauthService.requireForCurrentUser();
        List<GithubRepositorySummary> items = githubClient.listUserRepositories(
                conn.getAccessToken(), 50);
        return items.stream()
                .map(this::toCatalogItem)
                .toList();
    }

    @Transactional
    public Repository connect(UUID workspaceId, Long githubId, String defaultBranch) {
        if (workspaceId == null) {
            throw new BadRequestException("workspaceId is required");
        }
        if (githubId == null || githubId <= 0) {
            throw new BadRequestException("githubId must be a positive number");
        }
        UUID userId = CurrentUser.requireId();
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId).isEmpty()) {
            throw new ForbiddenException("You are not a member of this workspace");
        }
        Optional<Repository> existing = repositoryRepository.findByGithubId(githubId);
        if (existing.isPresent()) {
            Repository row = existing.get();
            if (row.getStatus() == Repository.Status.DISCONNECTED
                    && row.getWorkspaceId().equals(workspaceId)) {
                // Repo was previously disconnected from this same workspace — re-attach it.
                log.info("Re-attaching previously disconnected repository {} to workspace {}",
                        row.getFullName(), workspaceId);
                row.setStatus(Repository.Status.CONNECTED);
                row.setLastSyncedAt(OffsetDateTime.now());
                Repository reattached = repositoryRepository.save(row);
                // Best-effort: register a webhook again.
                tryRegisterWebhook(reattached, userId);
                return reattached;
            }
            throw new ConflictException("This repository is already connected to a workspace");
        }
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("Workspace not found"));

        GithubConnection conn = oauthService.requireForCurrentUser();
        List<GithubRepositorySummary> catalog = githubClient.listUserRepositories(conn.getAccessToken(), 100);
        GithubRepositorySummary match = catalog.stream()
                .filter(r -> r.id().equals(githubId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Repository not found in the authenticated GitHub account. "
                                + "Make sure it is visible to the connected GitHub identity."));

        Repository repo = Repository.create(
                workspaceId,
                match.id(),
                match.ownerLogin(),
                match.name(),
                defaultBranch != null ? defaultBranch : match.defaultBranch(),
                match.htmlUrl(),
                match.description(),
                match.isPrivate(),
                userId);
        Repository saved = repositoryRepository.save(repo);
        log.info("Repository {} connected to workspace {} by user {}",
                saved.getFullName(), workspaceId, userId);
        tryRegisterWebhook(saved, userId);
        return saved;
    }

    private void tryRegisterWebhook(Repository saved, UUID userId) {
        // Best-effort: register a webhook on GitHub so future pushes are
        // delivered to POST /api/v1/github/webhook. We swallow failures
        // (sandbox mode cannot reach GitHub, OAuth scope might be
        // missing, etc.) so the user can still see the repo connected.
        try {
            GithubConnection conn = oauthService.findForUser(userId).orElse(null);
            if (conn == null) {
                log.debug("Skipping webhook registration for {}: no GitHub connection for user {}",
                        saved.getFullName(), userId);
                return;
            }
            Long hookId = githubClient.createWebhook(
                    conn.getAccessToken(),
                    saved.getOwner(),
                    saved.getName(),
                    appPublicUrl + "/api/v1/github/webhook",
                    githubProperties.getWebhook().getSecret(),
                    List.of("push", "pull_request", "ping"));
            if (hookId != null) {
                saved.setWebhookId(hookId);
                repositoryRepository.save(saved);
            }
        } catch (Exception ex) {
            log.warn("Could not register GitHub webhook for {}: {}",
                    saved.getFullName(), ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Repository> listForWorkspace(UUID workspaceId) {
        requireMembership(workspaceId);
        return repositoryRepository.findByWorkspaceIdOrderByConnectedAtDesc(workspaceId);
    }

    @Transactional
    public void disconnect(UUID workspaceId, UUID repositoryId) {
        requireMembership(workspaceId);
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }
        // Best-effort: delete the GitHub webhook so we stop receiving
        // events for this repo. Failures are logged inside
        // deleteWebhook() and must not block the user-facing disconnect.
        if (repo.getWebhookId() != null) {
            try {
                GithubConnection conn = oauthService.findForCurrentUser().orElse(null);
                if (conn != null) {
                    githubClient.deleteWebhook(conn.getAccessToken(),
                            repo.getOwner(), repo.getName(), repo.getWebhookId());
                }
            } catch (Exception ex) {
                log.warn("Failed to delete GitHub webhook for {}: {}",
                        repo.getFullName(), ex.getMessage());
            }
            repo.setWebhookId(null);
        }
        repo.setStatus(Repository.Status.DISCONNECTED);
        repositoryRepository.save(repo);
        log.info("Repository {} disconnected from workspace {}", repo.getFullName(), workspaceId);
    }

    @Transactional
    public Repository sync(UUID workspaceId, UUID repositoryId) {
        requireMembership(workspaceId);
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }
        // If the repo is currently DISCONNECTED, treat this sync call as
        // a reconnection request: flip status to CONNECTED and register
        // a fresh webhook. This way the user can recover a repo without
        // having to remove and re-add it.
        if (repo.getStatus() == Repository.Status.DISCONNECTED) {
            UUID userId = CurrentUser.requireId();
            repo.setStatus(Repository.Status.CONNECTED);
            repo.setLastSyncedAt(OffsetDateTime.now());
            log.info("Repository {} reconnected via sync by user {}", repo.getFullName(), userId);
            tryRegisterWebhook(repo, userId);
            return repositoryRepository.save(repo);
        }
        // Real re-sync: fetch current metadata from GitHub and refresh
        // description, default branch, visibility, and the timestamp.
        GithubConnection conn = oauthService.requireForCurrentUser();
        try {
            GithubRepositorySummary fresh = githubClient.getRepository(
                    conn.getAccessToken(), repo.getOwner(), repo.getName());
            if (fresh.description() != null) repo.setDescription(fresh.description());
            if (fresh.defaultBranch() != null) repo.setDefaultBranch(fresh.defaultBranch());
            repo.setPrivate(fresh.isPrivate());
            repo.setLastSyncedAt(OffsetDateTime.now());
            log.info("Repository {} re-synced from GitHub", repo.getFullName());
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            oauthService.markConnectionStatus(conn.getUserId(), GithubConnection.Status.EXPIRED);
            throw e;
        }
        return repositoryRepository.save(repo);
    }

    private void requireMembership(UUID workspaceId) {
        UUID userId = CurrentUser.requireId();
        workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this workspace"));
    }

    private GithubRepositoryCatalogItem toCatalogItem(GithubRepositorySummary r) {
        return new GithubRepositoryCatalogItem(
                r.id(), r.name(), r.fullName(), r.ownerLogin(),
                r.defaultBranch(), r.htmlUrl(), r.description(),
                r.isPrivate(), r.isArchived(), r.isDisabled());
    }

    @Transactional
    public Repository setAutoUpdate(UUID workspaceId, UUID repositoryId, boolean enabled) {
        requireMembership(workspaceId);
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }
        repo.setAutoUpdateEnabled(enabled);
        Repository saved = repositoryRepository.save(repo);
        log.info("Repository {} autoUpdateEnabled = {}", saved.getFullName(), enabled);
        return saved;
    }

    /**
     * Fetches a single repository by its GitHub URL and returns it as a catalog item.
     *
     * @param url GitHub repository URL (e.g. https://github.com/owner/repo)
     * @return repository info as a catalog item
     * @throws NotFoundException if the repository is not found or not accessible
     */
    public GithubRepositoryCatalogItem getRepositoryByUrl(String url) {
        GithubConnection conn = oauthService.requireForCurrentUser();
        GithubRepositorySummary r = githubClient.fetchRepositoryByUrl(conn.getAccessToken(), url);
        return toCatalogItem(r);
    }
}