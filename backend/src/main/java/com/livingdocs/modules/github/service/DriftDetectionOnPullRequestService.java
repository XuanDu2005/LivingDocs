package com.livingdocs.modules.github.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.service.DriftAlertService;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Detects documentation drift automatically when a pull request lands.
 *
 * <p>The specification asks for drift alerts to appear "automatically
 * when a pull request is opened". Until now the pull-request webhook
 * only triggered an <em>auto-update</em> (regenerate the documentation),
 * and the drift alert was only ever produced when a human pressed
 * "Scan" in the UI. This service closes that gap: on every pull-request
 * webhook it compares the code before and after the change against the
 * documentation that covers it and files the resulting alerts.
 *
 * <p>The comparison needs the <em>before</em> content of each changed
 * file, which the webhook payload does not carry — so it is fetched
 * from GitHub at the base branch, while the "after" content is fetched
 * at the head branch.
 */
@Service
public class DriftDetectionOnPullRequestService {

    private static final Logger log =
            LoggerFactory.getLogger(DriftDetectionOnPullRequestService.class);

    /** Hard cap on the number of files compared in one run. */
    private static final int MAX_FILES_PER_RUN = 20;

    private final CodeEntityRepository codeEntityRepository;
    private final CodeDocumentLinkRepository linkRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final GithubClient githubClient;
    private final AiDocumentationService aiDocumentationService;
    private final DriftAlertService driftAlertService;

    public DriftDetectionOnPullRequestService(CodeEntityRepository codeEntityRepository,
                                               CodeDocumentLinkRepository linkRepository,
                                               DocumentRepository documentRepository,
                                               WorkspaceMemberRepository memberRepository,
                                               GithubClient githubClient,
                                               AiDocumentationService aiDocumentationService,
                                               DriftAlertService driftAlertService) {
        this.codeEntityRepository = codeEntityRepository;
        this.linkRepository = linkRepository;
        this.documentRepository = documentRepository;
        this.memberRepository = memberRepository;
        this.githubClient = githubClient;
        this.aiDocumentationService = aiDocumentationService;
        this.driftAlertService = driftAlertService;
    }

    /**
     * Run a drift detection pass for the files a pull request touches.
     *
     * @param repo      the repository the pull request belongs to
     * @param payload   the raw webhook payload
     * @param prId      the local id of the stored pull request, if any
     * @param accessToken token used to fetch the before/after contents
     * @return the number of drift alerts that were filed
     */
    @Transactional
    public int detectForPullRequest(Repository repo,
                                    JsonNode payload,
                                    UUID prId,
                                    String accessToken) {
        Set<String> changedFiles = changedFiles(payload);
        if (changedFiles.isEmpty()) {
            log.debug("Pull request carries no changed files — no drift detection");
            return 0;
        }

        String baseBranch = payload.path("pull_request").path("base").path("ref").asText(null);
        String headBranch = payload.path("pull_request").path("head").path("ref").asText(null);
        if (baseBranch == null) {
            baseBranch = repo.getDefaultBranch();
        }
        if (headBranch == null) {
            headBranch = baseBranch;
        }

        return detect(repo, changedFiles, baseBranch, headBranch, prId, accessToken);
    }

    /**
     * Run a drift detection pass for an explicit set of changed files.
     *
     * <p>This is the entry point the CI path uses. The webhook path
     * derives the changed files from the payload it was handed; a CI
     * job has no payload, it knows the pull request and asks GitHub for
     * the file list.
     *
     * <p>The merge policy is honoured here, not in the caller: a
     * workspace whose policy is {@code BLOCK} always gets its drift
     * scanned on a pull request, even when the workspace turned the
     * automatic PR-driven update off. The merge gate can only block a
     * merge if the alerts it reasons about actually exist.
     *
     * @param repo        the repository the pull request belongs to
     * @param changedFiles the paths the pull request touches
     * @param baseBranch  the branch the pull request merges into
     * @param headBranch  the branch the pull request proposes
     * @param prId        the local id of the stored pull request, if any
     * @param accessToken the GitHub token used to fetch the contents
     * @return the number of drift alerts that were filed
     */
    @Transactional
    public int detect(Repository repo,
                      Set<String> changedFiles,
                      String baseBranch,
                      String headBranch,
                      UUID prId,
                      String accessToken) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            log.debug("No changed files given — no drift detection");
            return 0;
        }

        // Which documents document the changed files?
        List<CodeEntity> entities = codeEntityRepository.findByFilePathIn(changedFiles);
        if (entities.isEmpty()) {
            log.debug("No indexed code entities for the changed files of {} — no drift to detect",
                    repo.getFullName());
            return 0;
        }
        Set<UUID> entityIds = entities.stream().map(CodeEntity::getId).collect(Collectors.toSet());
        List<CodeDocumentLink> links = linkRepository.findByCodeEntityIdIn(entityIds);
        if (links.isEmpty()) {
            log.debug("The changed code is not linked to any document — no drift to detect");
            return 0;
        }
        Set<UUID> documentIds = links.stream()
                .map(CodeDocumentLink::getDocumentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Download the before / after content of the changed files.
        List<AiDtos.SourceFile> filesBefore = download(repo, changedFiles, baseBranch, accessToken);
        List<AiDtos.SourceFile> filesAfter = download(repo, changedFiles, headBranch, accessToken);
        if (filesAfter.isEmpty()) {
            log.warn("Could not download the changed files of {} at {} — no drift detection",
                    repo.getFullName(), headBranch);
            return 0;
        }

        int total = 0;
        for (UUID documentId : documentIds) {
            Document document = documentRepository.findById(documentId).orElse(null);
            if (document == null || !document.getWorkspaceId().equals(repo.getWorkspaceId())) {
                continue;
            }
            try {
                // The actor is the member who owns the repository
                // connection: the drift alert must be attributed to a
                // real user, because the alerting path checks
                // workspace membership.
                UUID actorId = repo.getConnectedBy();
                if (!isMember(repo.getWorkspaceId(), actorId)) {
                    actorId = firstMember(repo.getWorkspaceId());
                }
                if (actorId == null) {
                    log.debug("No workspace member to attribute the drift alert of {} to",
                            document.getTitle());
                    continue;
                }
                int filed = aiDocumentationService.detectDriftForPullRequest(
                        actorId, repo.getWorkspaceId(), repo.getId(), prId, documentId,
                        filesBefore, filesAfter);
                total += filed;
                if (filed > 0) {
                    log.info("Filed {} drift alert(s) for document {} from pull request in {}",
                            filed, document.getTitle(), repo.getFullName());
                }
            } catch (Exception ex) {
                // A drift detection failure must never fail the webhook.
                log.warn("Drift detection failed for document {}: {}",
                        document.getTitle(), ex.getMessage());
            }
        }
        return total;
    }

    /**
     * The files a pull request touches.
     *
     * <p>Reuses the same extraction as the auto-update path so the two
     * features always agree on what "the changed files" are.
     */
    Set<String> changedFiles(JsonNode payload) {
        Set<String> changed = new LinkedHashSet<>();
        JsonNode commits = payload.path("commits");
        if (commits.isArray()) {
            for (JsonNode commit : commits) {
                commit.path("added").forEach(n -> changed.add(n.asText()));
                commit.path("modified").forEach(n -> changed.add(n.asText()));
            }
        }
        return changed;
    }

    private List<AiDtos.SourceFile> download(Repository repo, Set<String> paths,
                                              String branch, String accessToken) {
        List<AiDtos.SourceFile> files = new ArrayList<>();
        int count = 0;
        for (String path : paths) {
            if (count >= MAX_FILES_PER_RUN) {
                log.info("Reached the {}-file cap for one drift comparison — truncating",
                        MAX_FILES_PER_RUN);
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

    private boolean isMember(UUID workspaceId, UUID userId) {
        if (userId == null) {
            return false;
        }
        return memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }

    private UUID firstMember(UUID workspaceId) {
        return memberRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(m -> m.getUserId())
                .findFirst()
                .orElse(null);
    }
}
