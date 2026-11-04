package com.livingdocs.modules.github.service;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubPullRequestFile;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the "before" and "after" content of the files a pull request
 * touches.
 *
 * <p>The drift analyser needs the two revisions of the changed source
 * files to decide whether the documentation still matches the code. The
 * pull-request webhook path has that information at hand (see
 * {@link DriftDetectionOnPullRequestService}), but the on-demand scan
 * in the UI does not — so this service reconstructs it from GitHub:
 *
 * <ol>
 *   <li>{@code GET /pulls/{n}/files} gives the changed paths,</li>
 *   <li>the contents at the base branch are the "before",</li>
 *   <li>the contents at the head branch are the "after".</li>
 * </ol>
 */
@Service
public class PullRequestDiffService {

    private static final Logger log =
            LoggerFactory.getLogger(PullRequestDiffService.class);

    /** Hard cap on the number of files compared in one run. */
    private static final int MAX_FILES = 20;

    private final GithubClient githubClient;
    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final GithubOAuthService oauthService;

    public PullRequestDiffService(GithubClient githubClient,
                                  RepositoryRepository repositoryRepository,
                                  PullRequestRepository pullRequestRepository,
                                  GithubOAuthService oauthService) {
        this.githubClient = githubClient;
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.oauthService = oauthService;
    }

    /**
     * Resolve the before / after content for a stored pull request.
     *
     * <p>Uses the caller's GitHub connection to read the file contents.
     * If the connection is missing the scan falls back to empty lists
     * rather than failing — the caller can still run the scan against
     * whatever it already has.
     */
    @Transactional(readOnly = true)
    public DiffFiles resolveForPullRequest(UUID workspaceId,
                                           UUID repositoryId,
                                           UUID pullRequestId) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new com.livingdocs.common.exception.NotFoundException(
                        "Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new com.livingdocs.common.exception.NotFoundException(
                    "Repository not found in this workspace");
        }
        PullRequest pr = pullRequestRepository.findById(pullRequestId)
                .orElseThrow(() -> new com.livingdocs.common.exception.NotFoundException(
                        "Pull request not found"));
        if (!pr.getRepositoryId().equals(repositoryId)) {
            throw new com.livingdocs.common.exception.NotFoundException(
                    "Pull request does not belong to this repository");
        }

        String accessToken = oauthService.findForCurrentUser()
                .map(GithubConnection::getAccessToken)
                .orElse(null);
        if (accessToken == null || accessToken.isBlank()) {
            log.info("No GitHub connection — cannot resolve the diff of pull request {}", pr.getId());
            return new DiffFiles(List.of(), List.of());
        }
        return resolve(repo, pr.getGithubPrNumber(),
                pr.getBaseBranch(), pr.getHeadBranch(), accessToken);
    }

    /**
     * The before / after content of the files a pull request touches.
     *
     * @param repo        the repository the pull request belongs to
     * @param prNumber    the GitHub pull-request number
     * @param baseBranch  the branch the pull request merges into
     * @param headBranch  the branch the pull request proposes
     * @param accessToken the GitHub token used to read the file contents
     * @return the two file lists; either may be empty when GitHub cannot
     *         deliver the content, but they are never {@code null}
     */
    public DiffFiles resolve(Repository repo,
                             long prNumber,
                             String baseBranch,
                             String headBranch,
                             String accessToken) {
        List<GithubPullRequestFile> prFiles = githubClient.listPullRequestFiles(
                accessToken, repo.getOwner(), repo.getName(), prNumber);
        if (prFiles.isEmpty()) {
            log.info("GitHub returned no files for {}/{}#{}",
                    repo.getFullName(), prNumber, prNumber);
            return new DiffFiles(List.of(), List.of());
        }

        Set<String> paths = new LinkedHashSet<>();
        for (GithubPullRequestFile f : prFiles) {
            if (f.path() != null && !f.path().isBlank()) {
                paths.add(f.path());
            }
        }

        String base = baseBranch != null && !baseBranch.isBlank()
                ? baseBranch : repo.getDefaultBranch();
        String head = headBranch != null && !headBranch.isBlank()
                ? headBranch : base;

        List<AiDtos.SourceFile> before = download(repo, paths, base, accessToken);
        List<AiDtos.SourceFile> after = download(repo, paths, head, accessToken);
        return new DiffFiles(before, after);
    }

    private List<AiDtos.SourceFile> download(Repository repo, Set<String> paths,
                                              String branch, String accessToken) {
        List<AiDtos.SourceFile> files = new ArrayList<>();
        int count = 0;
        for (String path : paths) {
            if (count >= MAX_FILES) {
                log.info("Reached the {}-file cap for one diff — truncating", MAX_FILES);
                break;
            }
            try {
                String content = githubClient.getFileContent(
                        accessToken, repo.getOwner(), repo.getName(), path, branch);
                if (content != null && !content.isBlank()) {
                    files.add(new AiDtos.SourceFile(path, content));
                    count++;
                }
            } catch (Exception e) {
                log.debug("Could not fetch {} at {}: {}", path, branch, e.getMessage());
            }
        }
        return files;
    }

    /**
     * The two revisions of the changed files.
     */
    public record DiffFiles(List<AiDtos.SourceFile> filesBefore,
                            List<AiDtos.SourceFile> filesAfter) {
    }
}
