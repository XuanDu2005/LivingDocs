package com.livingdocs.modules.cicd.service;

import com.livingdocs.modules.ai.settings.GovernancePolicyService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubPullRequestFile;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.DriftDetectionOnPullRequestService;
import com.livingdocs.modules.github.service.DocumentAutoUpdateService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The CI-facing half of the merge gate.
 *
 * <p>This is what the generated GitHub Actions workflow calls. It does
 * two things, in this order:
 *
 * <ol>
 *   <li><b>Scan</b> — detect the documentation drift the pull request
 *       introduces, so the alerts the gate reasons about actually
 *       exist. Without this the gate would be evaluated against stale
 *       alerts, or against none at all.</li>
 *   <li><b>Gate</b> — ask {@link GovernancePolicyService} whether the
 *       workspace's merge policy allows the merge.</li>
 * </ol>
 *
 * <p>The policy comes from the workspace, never from the caller. The
 * old {@code ?blockOnCritical=true} query parameter made the workflow
 * decide on its own whether to fail, which meant the workspace setting
 * was decorative — a workflow generated before the policy was switched
 * to {@code BLOCK} kept failing the build, and one generated after it
 * kept passing. Now the workflow always asks, and the answer is
 * whatever the workspace says.
 *
 * <p>The scan is best-effort: a failure to detect drift is reported in
 * the verdict but does not by itself block the merge. The gate blocks
 * on the policy and the alerts, not on the scan having succeeded.
 */
@Service
public class MergeGateService {

    private static final Logger log = LoggerFactory.getLogger(MergeGateService.class);

    /** Hard cap on the number of files compared in one scan. */
    private static final int MAX_FILES_PER_RUN = 20;

    private final GovernancePolicyService governancePolicyService;
    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final DriftDetectionOnPullRequestService driftDetectionService;
    private final DocumentAutoUpdateService autoUpdateService;
    private final GithubClient githubClient;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;

    public MergeGateService(GovernancePolicyService governancePolicyService,
                            RepositoryRepository repositoryRepository,
                            PullRequestRepository pullRequestRepository,
                            DriftDetectionOnPullRequestService driftDetectionService,
                            DocumentAutoUpdateService autoUpdateService,
                            GithubClient githubClient,
                            WorkspaceService workspaceService,
                            AuditLogService auditLogService) {
        this.governancePolicyService = governancePolicyService;
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.driftDetectionService = driftDetectionService;
        this.autoUpdateService = autoUpdateService;
        this.githubClient = githubClient;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
    }

    /**
     * Evaluate the merge gate for one pull request.
     *
     * <p>The pull request is addressed by its GitHub number, because
     * that is what a CI job knows — the workflow does not carry
     * LivingDocs' internal pull-request id.
     *
     * @param workspaceId   the workspace the CI token authenticates
     * @param repositoryId  the repository the pull request belongs to
     * @param prNumber      the GitHub pull-request number
     */
    @Transactional
    public MergeGateVerdict evaluate(UUID workspaceId,
                                     UUID repositoryId,
                                     long prNumber) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new com.livingdocs.common.exception.NotFoundException(
                        "Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            // The token authenticates one workspace; it cannot be used
            // to act on another one.
            throw new com.livingdocs.common.exception.ForbiddenException(
                    "This repository does not belong to the workspace the CI token authenticates");
        }

        Optional<PullRequest> stored = pullRequestRepository
                .findByRepositoryIdAndGithubPrNumber(repositoryId, prNumber);

        int alertsFiled = 0;
        String scanNote = null;
        try {
            alertsFiled = scan(repo, prNumber, stored.orElse(null));
        } catch (Exception ex) {
            // A failed scan must not decide the merge. The gate reasons
            // about the alerts that exist; if the scan could not run,
            // that is reported but it does not manufacture a block.
            scanNote = "Drift scan failed: " + ex.getMessage();
            log.warn("Drift scan failed for {}/{}#{}: {}",
                    repo.getFullName(), prNumber, ex.getMessage());
        }

        UUID pullRequestId = stored.map(PullRequest::getId).orElse(null);
        var verdict = governancePolicyService.evaluateMergeGate(workspaceId, pullRequestId);

        MergeGateVerdict out = new MergeGateVerdict(
                workspaceId,
                repositoryId,
                prNumber,
                pullRequestId,
                verdict.policy(),
                verdict.severityThreshold().name(),
                verdict.blocked(),
                verdict.blockingAlertCount(),
                verdict.blockingAlertIds(),
                verdict.reason(),
                alertsFiled,
                scanNote);

        auditLogService.record(null, "CI", "merge.gate.evaluate",
                "pull_request", String.valueOf(prNumber),
                workspaceId,
                Map.of("repositoryId", repositoryId.toString(),
                        "pullRequestNumber", prNumber,
                        "policy", verdict.policy(),
                        "blocked", verdict.blocked(),
                        "blockingAlertCount", verdict.blockingAlertCount(),
                        "alertsFiled", alertsFiled));

        log.info("Merge gate for {}/{}#{}: policy={} blocked={} alerts={}",
                repo.getFullName(), prNumber, verdict.policy(),
                verdict.blocked(), verdict.blockingAlertCount());

        return out;
    }

    /**
     * Detect the drift the pull request introduces.
     *
     * <p>The changed files are read from GitHub (the CI job has no
     * webhook payload), and the before/after contents are downloaded at
     * the base and head branches.
     */
    private int scan(Repository repo, long prNumber, PullRequest pr) {
        String accessToken = autoUpdateService.resolveAccessToken(repo.getWorkspaceId());
        if (accessToken == null || accessToken.isBlank()) {
            log.info("No active GitHub connection for workspace {} — "
                    + "drift scan skipped for {}/{}#{}",
                    repo.getWorkspaceId(), repo.getFullName(), prNumber);
            return 0;
        }

        Set<String> changedFiles = changedFiles(repo, prNumber, accessToken, pr);
        if (changedFiles.isEmpty()) {
            log.info("GitHub reported no changed files for {}/{}#{} — nothing to scan",
                    repo.getFullName(), prNumber);
            return 0;
        }

        String baseBranch = pr != null && pr.getBaseBranch() != null
                ? pr.getBaseBranch() : repo.getDefaultBranch();
        String headBranch = pr != null && pr.getHeadBranch() != null
                ? pr.getHeadBranch() : baseBranch;

        return driftDetectionService.detect(
                repo, changedFiles, baseBranch, headBranch,
                pr == null ? null : pr.getId(), accessToken);
    }

    /**
     * The files the pull request touches.
     *
     * <p>Read from the GitHub API. The stored pull-request row carries
     * the branches but not the file list, so the API is the only
     * source — and it is the same source the on-demand scan uses, so
     * the two always agree on what "the changed files" are.
     */
    private Set<String> changedFiles(Repository repo,
                                     long prNumber,
                                     String accessToken,
                                     PullRequest pr) {
        Set<String> paths = new LinkedHashSet<>();
        List<GithubPullRequestFile> files = githubClient.listPullRequestFiles(
                accessToken, repo.getOwner(), repo.getName(), prNumber);
        int count = 0;
        for (GithubPullRequestFile f : files) {
            if (count >= MAX_FILES_PER_RUN) {
                log.info("Reached the {}-file cap for the merge-gate scan of {}/{}#{} — truncating",
                        MAX_FILES_PER_RUN, repo.getFullName(), prNumber);
                break;
            }
            if (f.path() != null && !f.path().isBlank()) {
                paths.add(f.path());
                count++;
            }
        }
        return paths;
    }

    /**
     * The verdict the CI job acts on.
     *
     * @param blocked         whether the merge must not proceed
     * @param policy          the effective policy ({@code WARN} / {@code BLOCK})
     * @param severityThreshold the severity at or above which drift blocks
     * @param alertsFiled     how many alerts this scan produced
     * @param scanNote        why the scan did not run, when it did not
     */
    public record MergeGateVerdict(
            UUID workspaceId,
            UUID repositoryId,
            long pullRequestNumber,
            UUID pullRequestId,
            String policy,
            String severityThreshold,
            boolean blocked,
            int blockingAlertCount,
            List<UUID> blockingAlertIds,
            String reason,
            int alertsFiled,
            String scanNote) {
    }
}
