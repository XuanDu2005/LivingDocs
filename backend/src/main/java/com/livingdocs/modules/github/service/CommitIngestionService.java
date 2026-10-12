package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubCommit;
import com.livingdocs.modules.github.model.Commit;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ingests commits from a connected GitHub repository and surfaces them to
 * the frontend. Idempotent: re-ingesting the same commit is a no-op
 * because the (repository_id, sha) unique constraint blocks duplicates.
 */
@Service
public class CommitIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CommitIngestionService.class);

    private final CommitRepository commitRepository;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final GithubClient githubClient;
    private final GithubOAuthService oauthService;

    public CommitIngestionService(CommitRepository commitRepository,
                                  RepositoryRepository repositoryRepository,
                                  WorkspaceMemberRepository workspaceMemberRepository,
                                  GithubClient githubClient,
                                  GithubOAuthService oauthService) {
        this.commitRepository = commitRepository;
        this.repositoryRepository = repositoryRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.githubClient = githubClient;
        this.oauthService = oauthService;
    }

    /**
     * Fetch the latest commits for a repository and persist any that we
     * don't already have. Returns the newly-stored rows so the caller can
     * show a count of "X new commits ingested".
     */
    @Transactional
    public List<Commit> ingestLatest(UUID workspaceId, UUID repositoryId, int perPage) {
        Repository repo = requireRepo(workspaceId, repositoryId);
        GithubConnection conn = oauthService.requireForCurrentUser();
        List<GithubCommit> raw;
        try {
            raw = githubClient.listCommits(conn.getAccessToken(),
                    repo.getOwner(), repo.getName(), Math.min(Math.max(perPage, 1), 100), null);
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            // Mark the connection as EXPIRED so the UI shows the reconnect banner.
            oauthService.markConnectionStatus(conn.getUserId(), GithubConnection.Status.EXPIRED);
            throw e;
        }
        List<Commit> stored = new ArrayList<>();
        for (GithubCommit g : raw) {
            if (commitRepository.findByRepositoryIdAndSha(repositoryId, g.sha()).isPresent()) {
                continue;
            }
            Commit c = new Commit(
                    repositoryId, g.sha(), g.message(),
                    g.authorName(), g.authorEmail(),
                    nullToNow(g.authoredAt()), nullToNow(g.committedAt()),
                    g.url(), null);
            stored.add(commitRepository.save(c));
        }
        log.info("Ingested {} new commit(s) for repo {}", stored.size(), repo.getFullName());
        return stored;
    }

    @Transactional(readOnly = true)
    public List<Commit> listForRepository(UUID workspaceId, UUID repositoryId, int limit) {
        requireRepo(workspaceId, repositoryId);
        return commitRepository.findAllByRepositoryIdOrderByAuthoredAtDesc(
                repositoryId, org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 100)));
    }

    private Repository requireRepo(UUID workspaceId, UUID repositoryId) {
        UUID userId = CurrentUser.requireId();
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId).isEmpty()) {
            throw new ForbiddenException("You are not a member of this workspace");
        }
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }
        return repo;
    }

    private static OffsetDateTime nullToNow(OffsetDateTime t) {
        return t != null ? t : OffsetDateTime.now();
    }
}
