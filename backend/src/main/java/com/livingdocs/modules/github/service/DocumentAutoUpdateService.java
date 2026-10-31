package com.livingdocs.modules.github.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.GithubConnectionRepository;
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
 * Triggers AI regeneration for the documents affected by a code change.
 *
 * <p>This is the "self-updating" core of LivingDocs. The flow is:
 * <ol>
 *   <li>A push (or PR) webhook tells us which files changed.</li>
 *   <li>We find the {@code code_entities} indexed for those files and
 *       the documents linked to them.</li>
 *   <li>We download the <em>new</em> content of those files from
 *       GitHub and hand it to the AI, which produces a new PENDING
 *       document version.</li>
 * </ol>
 *
 * <p>Design notes:
 * <ul>
 *   <li>The old {@code com.livingdocs.modules.user.service.WebhookService}
 *       did roughly this, but it was a second, parallel webhook path with
 *       no signature verification and a hard-coded random "system user"
 *       id. Everything now lives here, on the verified path.</li>
 *   <li>Generation is queued asynchronously through the Kafka pipeline,
 *       so a large push does not block the webhook response.</li>
 *   <li>Only documents with {@code auto_update_enabled} are touched —
 *       auto-updating every document on every commit would be noise.</li>
 * </ul>
 */
@Service
public class DocumentAutoUpdateService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoUpdateService.class);

    /** Hard cap on the number of files we pull into a single generation run. */
    private static final int MAX_FILES_PER_RUN = 40;

    private final ObjectMapper objectMapper;
    private final CodeEntityRepository codeEntityRepository;
    private final CodeDocumentLinkRepository linkRepository;
    private final DocumentRepository documentRepository;
    private final GithubConnectionRepository githubConnectionRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final GithubClient githubClient;
    private final AiDocumentationService aiDocumentationService;

    public DocumentAutoUpdateService(ObjectMapper objectMapper,
                                     CodeEntityRepository codeEntityRepository,
                                     CodeDocumentLinkRepository linkRepository,
                                     DocumentRepository documentRepository,
                                     GithubConnectionRepository githubConnectionRepository,
                                     WorkspaceMemberRepository workspaceMemberRepository,
                                     GithubClient githubClient,
                                     AiDocumentationService aiDocumentationService) {
        this.objectMapper = objectMapper;
        this.codeEntityRepository = codeEntityRepository;
        this.linkRepository = linkRepository;
        this.documentRepository = documentRepository;
        this.githubConnectionRepository = githubConnectionRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.githubClient = githubClient;
        this.aiDocumentationService = aiDocumentationService;
    }

    /**
     * Handle a code change for a repository.
     *
     * @param repo        the repository that changed
     * @param changedFilePaths paths that were added or modified
     * @param branch      the branch the change landed on
     * @param commitSha   the head commit of the change (may be null)
     * @param accessToken GitHub token used to fetch the new file contents
     * @return the number of documents that were queued for regeneration
     */
    @Transactional(readOnly = true)
    public int triggerAutoUpdate(Repository repo,
                                 Set<String> changedFilePaths,
                                 String branch,
                                 String commitSha,
                                 String accessToken) {
        if (changedFilePaths == null || changedFilePaths.isEmpty()) {
            return 0;
        }
        if (!repo.isAutoUpdateEnabled()) {
            log.debug("Auto-update disabled for repo {}, skipping", repo.getFullName());
            return 0;
        }

        // 1. Which code entities live in the changed files?
        List<CodeEntity> entities = codeEntityRepository.findByFilePathIn(changedFilePaths);
        if (entities.isEmpty()) {
            log.debug("No indexed code entities for {} changed file(s) in {} — nothing to update",
                    changedFilePaths.size(), repo.getFullName());
            return 0;
        }

        // 2. Which documents reference those entities?
        Set<UUID> entityIds = entities.stream().map(CodeEntity::getId).collect(Collectors.toSet());
        List<CodeDocumentLink> links = linkRepository.findByCodeEntityIdIn(entityIds);
        if (links.isEmpty()) {
            log.debug("Changed code is not linked to any document — nothing to update");
            return 0;
        }

        Set<UUID> documentIds = links.stream()
                .map(CodeDocumentLink::getDocumentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 3. Download the new content of the changed files once — every
        //    affected document is regenerated from the same source set.
        List<AiDtos.SourceFile> sourceFiles = downloadChangedFiles(
                repo, changedFilePaths, branch, accessToken);
        if (sourceFiles.isEmpty()) {
            log.warn("Could not download any changed file for {} — skipping auto-update",
                    repo.getFullName());
            return 0;
        }

        // 4. Queue a regeneration for every document that opted in.
        int queued = 0;
        for (UUID documentId : documentIds) {
            Document document = documentRepository.findById(documentId).orElse(null);
            if (document == null) {
                continue;
            }
            if (!document.isAutoUpdateEnabled()) {
                log.debug("Document {} has auto-update disabled — skipping", document.getTitle());
                continue;
            }
            if (!document.getWorkspaceId().equals(repo.getWorkspaceId())) {
                // Defensive: a link should never cross workspaces.
                continue;
            }
            try {
                aiDocumentationService.generateForDocument(
                        null,                       // system-initiated
                        documentId,
                        sourceFiles,
                        document.getDocType() != null ? document.getDocType() : "MODULE_GUIDE",
                        null,
                        "Update the documentation to reflect the latest source changes.",
                        commitSha,
                        null);
                queued++;
                log.info("Queued AI auto-update for document {} (repo {}, commit {})",
                        document.getTitle(), repo.getFullName(), shortSha(commitSha));
            } catch (Exception e) {
                log.warn("Failed to queue auto-update for document {}: {}",
                        document.getTitle(), e.getMessage());
            }
        }
        return queued;
    }

    /**
     * Extract the set of added/modified file paths from a push payload.
     *
     * <p>GitHub sends the changed files inside each commit's
     * {@code added} / {@code modified} / {@code removed} arrays.
     */
    public Set<String> changedFilesFromPush(JsonNode payload) {
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

    /**
     * Extract the set of files a pull request touches.
     *
     * <p>The pull-request webhook payload carries a {@code changed_files}
     * count but not the file list itself, so the list is derived from the
     * {@code added}/{@code modified}/{@code removed} arrays of each commit
     * in the PR. Pull-request payloads always include the commit list,
     * unlike the shallow push payload.
     */
    public Set<String> changedFilesFromPullRequest(JsonNode payload) {
        Set<String> changed = new LinkedHashSet<>();
        JsonNode commits = payload.path("commits");
        if (commits.isArray()) {
            for (JsonNode commit : commits) {
                commit.path("added").forEach(n -> changed.add(n.asText()));
                commit.path("modified").forEach(n -> changed.add(n.asText()));
                commit.path("removed").forEach(n -> changed.add(n.asText()));
            }
        }
        // Fall back to the head-commit payload when the PR payload does
        // not enumerate its commits.
        if (changed.isEmpty()) {
            JsonNode headCommit = payload.path("pull_request").path("head");
            headCommit.path("added").forEach(n -> changed.add(n.asText()));
            headCommit.path("modified").forEach(n -> changed.add(n.asText()));
        }
        return changed;
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private List<AiDtos.SourceFile> downloadChangedFiles(Repository repo,
                                                          Set<String> changedFilePaths,
                                                          String branch,
                                                          String accessToken) {
        List<AiDtos.SourceFile> files = new ArrayList<>();
        String owner = repo.getOwner();
        String name = repo.getName();
        int count = 0;
        for (String path : changedFilePaths) {
            if (count >= MAX_FILES_PER_RUN) {
                log.info("Reached the {}-file cap for one auto-update run — truncating", MAX_FILES_PER_RUN);
                break;
            }
            try {
                String content = githubClient.getFileContent(accessToken, owner, name, path, branch);
                if (content != null && !content.isBlank()) {
                    files.add(new AiDtos.SourceFile(path, content));
                    count++;
                }
            } catch (Exception e) {
                log.warn("Failed to download {} from {}/{}: {}", path, owner, name, e.getMessage());
            }
        }
        return files;
    }

    /**
     * Find a usable GitHub access token for the repository.
     *
     * <p>Webhooks are not associated with a user, so we look for an
     * active connection belonging to one of the workspace's members.
     * Returns {@code null} when nobody in the workspace has connected
     * GitHub — the caller then skips the download step instead of
     * failing the whole webhook.
     *
     * <p>The lookup MUST be scoped to the workspace. Returning an
     * arbitrary platform-wide token would let one workspace's webhook
     * read source code with another tenant's GitHub credential.
     */
    @Transactional(readOnly = true)
    public String resolveAccessToken(UUID workspaceId) {
        List<GithubConnection> active = githubConnectionRepository.findAllByStatus(
                GithubConnection.Status.ACTIVE);
        if (workspaceId == null) {
            return null;
        }
        Set<UUID> memberIds = workspaceMemberRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(m -> m.getUserId())
                .collect(Collectors.toSet());
        if (memberIds.isEmpty()) {
            return null;
        }
        return active.stream()
                .filter(c -> memberIds.contains(c.getUserId()))
                .map(GithubConnection::getAccessToken)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) return "unknown";
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
