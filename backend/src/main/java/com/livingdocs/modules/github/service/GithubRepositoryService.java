package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubRepositorySummary;
import com.livingdocs.modules.github.dto.GithubRepositoryCatalogItem;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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

    public GithubRepositoryService(RepositoryRepository repositoryRepository,
                                   WorkspaceRepository workspaceRepository,
                                   WorkspaceMemberRepository workspaceMemberRepository,
                                   GithubOAuthService oauthService,
                                   GithubClient githubClient) {
        this.repositoryRepository = repositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.oauthService = oauthService;
        this.githubClient = githubClient;
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
        if (repositoryRepository.findByGithubId(githubId).isPresent()) {
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
        return saved;
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
        // Real implementation would re-fetch from GitHub and refresh metadata.
        // For the foundation phase we simply record the sync timestamp.
        repo.setLastSyncedAt(OffsetDateTime.now());
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