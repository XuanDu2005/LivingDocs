package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubClient.GithubFileWrite;
import com.livingdocs.modules.github.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes files into a connected repository — for seeding test data.
 *
 * <p>This is a development aid, not a product feature: it lets a
 * developer push a code change to a demo repository so that the drift
 * detector has something real to detect. It is only reachable through
 * {@code GithubSeedController}, which itself only exists in sandbox
 * mode.
 *
 * <p>The GitHub credential is resolved by
 * {@link GithubCredentialService} — the same service every other
 * GitHub call uses — so a dead token is reported as such instead of
 * failing halfway through a multi-file commit.
 */
@Service
public class GithubSeedService {

    private static final Logger log = LoggerFactory.getLogger(GithubSeedService.class);

    private final GithubClient githubClient;
    private final GithubCredentialService credentialService;

    public GithubSeedService(GithubClient githubClient,
                             GithubCredentialService credentialService) {
        this.githubClient = githubClient;
        this.credentialService = credentialService;
    }

    /**
     * One file to write.
     */
    public record SeedFile(String path, String content) {
    }

    /**
     * The outcome of the write.
     */
    public record SeedResult(String commitSha, String branch, int filesWritten, String url) {
    }

    /**
     * Write the files into the repository as a single commit.
     *
     * @param repo          the target repository
     * @param branch        the branch to write to; the default branch
     *                      when {@code null}
     * @param commitMessage the commit message
     * @param files         the files to write
     */
    @Transactional
    public SeedResult writeFiles(Repository repo,
                                 String branch,
                                 String commitMessage,
                                 List<SeedFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("No files to write");
        }
        for (SeedFile f : files) {
            if (f.path() == null || f.path().isBlank()) {
                throw new BadRequestException("A file was given without a path");
            }
            if (f.path().contains("..") || f.path().startsWith("/") || f.path().contains("\\")) {
                throw new BadRequestException("Illegal file path: " + f.path());
            }
        }

        String targetBranch = branch == null || branch.isBlank()
                ? repo.getDefaultBranch()
                : branch;

        // A credential that GitHub no longer accepts must be reported
        // as such — a half-written repository is much worse than a
        // clear failure.
        String token = credentialService.resolveTokenForRepository(repo, null);
        if (token == null || token.isBlank()) {
            throw new BadRequestException(
                    "No working GitHub connection is available for this repository. "
                            + "Reconnect the GitHub account and try again.");
        }

        List<GithubFileWrite> writes = files.stream()
                .map(f -> new GithubFileWrite(f.path(),
                        f.content() == null ? "" : f.content()))
                .toList();

        String commitSha = githubClient.commitFiles(
                token,
                repo.getOwner(),
                repo.getName(),
                targetBranch,
                writes,
                commitMessage,
                "LivingDocs",
                "livingdocs@local");

        log.info("Seeded {} file(s) into {}/{}@{} as commit {}",
                files.size(), repo.getOwner(), repo.getName(), targetBranch, commitSha);

        return new SeedResult(commitSha, targetBranch, files.size(),
                "https://github.com/" + repo.getOwner() + "/" + repo.getName()
                        + "/commit/" + commitSha);
    }
}
