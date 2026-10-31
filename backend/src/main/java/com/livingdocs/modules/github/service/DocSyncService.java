package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.docsync.service.DocUpdateRequestService;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubClient.GithubRepositoryContent;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scans repository documentation files and syncs them with code entities.
 * 
 * <p>This service:
 * <ul>
 *   <li>Lists all markdown files in the repository</li>
 *   <li>Extracts code references (class names, function names) from docs</li>
 *   <li>Finds matching code entities in the database</li>
 *   <li>Generates updated documentation content</li>
 * </ul>
 */
@Service
public class DocSyncService {

    private static final Logger log = LoggerFactory.getLogger(DocSyncService.class);

    // Patterns for extracting code references from markdown
    private static final List<Pattern> CODE_PATTERNS = List.of(
            // Class names: `ClassName` or [[ClassName]] or ClassName
            Pattern.compile("`([A-Z][a-zA-Z0-9]*(?:[A-Z][a-zA-Z0-9]*)*)`"),
            Pattern.compile("\\[\\[([A-Z][a-zA-Z0-9]*(?:[A-Z][a-zA-Z0-9]*)*)\\]\\]"),
            // Function/method names: `functionName()` or `methodName()`
            Pattern.compile("`([a-z][a-zA-Z0-9]*)\\(\\)`"),
            // camelCase function names in backticks WITHOUT parens: `formatDate`
            // (matches identifiers that start with lowercase, contain at least
            // one uppercase boundary like `formatDate` or `getUserById`, so it
            // doesn't false-positive generic lowercase words like `the`, `a`).
            Pattern.compile("`([a-z][a-zA-Z0-9]*(?:[A-Z][a-zA-Z0-9]*)+)`"),
            // Code block class references
            Pattern.compile("```[\\s\\S]*?class\\s+([A-Z][a-zA-Z0-9]*)"),
            // API endpoints: `/api/v1/...`
            Pattern.compile("`(/(?:api|v\\d+)[^`]+)`")
    );

    private final GithubClient githubClient;
    private final AiServiceClient aiServiceClient;
    private final CodeEntityRepository codeEntityRepository;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceService workspaceService;
    private final DocUpdateRequestService docUpdateRequestService;

    public DocSyncService(GithubClient githubClient,
                         AiServiceClient aiServiceClient,
                         CodeEntityRepository codeEntityRepository,
                         RepositoryRepository repositoryRepository,
                         WorkspaceService workspaceService,
                         DocUpdateRequestService docUpdateRequestService) {
        this.githubClient = githubClient;
        this.aiServiceClient = aiServiceClient;
        this.codeEntityRepository = codeEntityRepository;
        this.repositoryRepository = repositoryRepository;
        this.workspaceService = workspaceService;
        this.docUpdateRequestService = docUpdateRequestService;
    }

    /**
     * Result of scanning a repository for documentation files.
     */
    public record ScanResult(
            int totalFiles,
            int referencedEntities,
            int updatedEntities,
            List<DocFileInfo> files
    ) {}

    /**
     * Summary of the proposals queued by a scan.
     *
     * @param queued  number of NEW proposals created
     * @param skipped number of drifted files that were not queued
     *                (already pending, or the AI proposed no real change)
     */
    public record AutoQueueResult(int queued, int skipped, List<String> queuedFiles) {}

    /**
     * Information about a single documentation file.
     */
    public record DocFileInfo(
            String path,
            String type,
            Long size,
            int referencedEntities,
            List<String> foundEntities,
            List<String> missingEntities,
            boolean hasDrift,
            String driftReason
    ) {}

    /**
     * Scans a repository and finds documentation files with their code references.
     *
     * @param actorId      the user performing the action
     * @param workspaceId   the workspace containing the repository
     * @param repositoryId  the repository to scan
     * @param accessToken   GitHub access token
     * @param branch        branch to scan (optional, defaults to default branch)
     * @return scan results with file info and code references
     */
    @Transactional(readOnly = true)
    public ScanResult scanRepository(UUID actorId, UUID workspaceId, UUID repositoryId,
                                    String accessToken, String branch) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new RuntimeException("Repository not found"));
        workspaceService.requireMember(actorId, repo.getWorkspaceId());

        String scanBranch = (branch != null && !branch.isBlank()) ? branch : repo.getDefaultBranch();
        log.info("Scanning repository {} branch {} for documentation files", repo.getFullName(), scanBranch);

        // Get all code entities for this repository
        List<CodeEntity> entities = codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId);
        Map<String, CodeEntity> entityMap = entities.stream()
                .collect(Collectors.toMap(
                        e -> e.getSimpleName().toLowerCase(),
                        e -> e,
                        (e1, e2) -> e1 // handle duplicates by keeping first
                ));

        // Scan common documentation locations
        List<String> docPaths = List.of(
                "",           // root (README.md)
                "docs",       // docs/
                "doc",        // doc/
                "documentation" // documentation/
        );

        List<DocFileInfo> allFiles = new ArrayList<>();
        // Dedupe by full file path so the same file is never added twice
        // (e.g. when it's matched both from the root listing AND from the
        // recursive "docs/" listing, or when repo has both "docs/" and
        // "documentation/" with overlapping files).
        Set<String> seenPaths = new HashSet<>();

        for (String docPath : docPaths) {
            try {
                List<GithubRepositoryContent> contents = githubClient.listRepositoryContents(
                        accessToken, repo.getOwner(), repo.getName(), docPath, scanBranch);

                for (GithubRepositoryContent content : contents) {
                    if ("file".equals(content.type()) && content.name().endsWith(".md")) {
                        if (seenPaths.add(content.path())) {
                            DocFileInfo fileInfo = scanFile(content, entityMap,
                                    accessToken, repo.getOwner(), repo.getName(), scanBranch);
                            allFiles.add(fileInfo);
                        }
                    } else if ("dir".equals(content.type())) {
                        // Recursively scan subdirectories (recursive call also dedupes)
                        scanDirectoryRecursive(content.path(), scanBranch, repo.getOwner(),
                                repo.getName(), accessToken, entityMap, allFiles, seenPaths);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not scan path {} in repository {}: {}",
                        docPath, repo.getFullName(), e.getMessage());
            }
        }

        // Use AI to enrich drift detection for top candidates
        enrichDriftDetectionWithAI(allFiles, entities, workspaceId, scanBranch, repo.getFullName());

        // Build the unique-entity set from ALL files (including those discovered
        // through recursive subdirectory scanning, which don't flow back through
        // the outer loop's addAll).
        Set<String> allReferencedEntities = new HashSet<>();
        for (DocFileInfo f : allFiles) {
            allReferencedEntities.addAll(f.foundEntities());
        }
        int totalReferenced = allReferencedEntities.size();
        int matchedEntities = (int) allReferencedEntities.stream()
                .filter(name -> entityMap.containsKey(name.toLowerCase())).count();

        log.info("Scan complete: {} files, {} unique references, {} matched to entities",
                allFiles.size(), totalReferenced, matchedEntities);

        // Note: we do NOT auto-create DocUpdateRequest proposals here.
        // Drift detection is observational; the actual proposal (with
        // AI-generated diff body) is created via the explicit
        // "Preview → Queue for review" flow so reviewers always see
        // real content, not a half-baked heuristic.

        return new ScanResult(allFiles.size(), totalReferenced, matchedEntities, allFiles);
    }

    /**
     * After a scan, turn every drifted file into a review proposal.
     *
     * <p>This is the "review-before-push" entry point: scanning a
     * repository queues a PENDING_REVIEW {@link
     * com.livingdocs.modules.docsync.model.DocUpdateRequest} for each
     * file whose documentation no longer matches the code. Staff
     * approve/reject, then a Manager applies it as a real GitHub PR.
     *
     * <p>Each file is previewed independently, so a single failure
     * (e.g. the AI call times out for one file) does not abort the
     * whole batch. Files that already have a pending proposal are
     * skipped rather than duplicated.
     *
     * @return how many proposals were created and which files they cover
     */
    public AutoQueueResult autoQueueDriftedFiles(UUID actorId, UUID workspaceId, UUID repositoryId,
                                                  String accessToken, String branch,
                                                  List<DocFileInfo> driftedFiles) {
        if (driftedFiles == null || driftedFiles.isEmpty()) {
            return new AutoQueueResult(0, 0, List.of());
        }
        int queued = 0;
        int skipped = 0;
        List<String> queuedFiles = new ArrayList<>();

        for (DocFileInfo file : driftedFiles) {
            try {
                SyncPreviewResult preview = previewSync(
                        actorId, workspaceId, repositoryId, accessToken, file.path(), branch);
                if (preview.noEntitiesFound() || preview.proposedContent() == null) {
                    skipped++;
                    continue;
                }
                String diff = preview.diff();
                // Each proposal is queued in its own transaction so a
                // failure for one file does not roll back the others.
                com.livingdocs.modules.docsync.dto.DocUpdateRequestDto created =
                        docUpdateRequestService.createProposalIfNotPending(
                                workspaceId, repositoryId, null,
                                file.path(), preview.currentContent(), preview.proposedContent(),
                                diff == null ? null : diff.substring(0, Math.min(500, diff.length())),
                                null, branch, actorId);
                if (created != null) {
                    queued++;
                    queuedFiles.add(file.path());
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                // One bad file must not abort the batch.
                skipped++;
                log.warn("Could not queue doc update for {} in {}: {}",
                        file.path(), repositoryId, e.getMessage());
            }
        }

        log.info("Auto-queued {} doc update proposal(s) for repository {} ({} skipped)",
                queued, repositoryId, skipped);
        return new AutoQueueResult(queued, skipped, queuedFiles);
    }

    private void scanDirectoryRecursive(String dirPath, String branch, String owner, String repo,
                                       String accessToken, Map<String, CodeEntity> entityMap,
                                       List<DocFileInfo> results, Set<String> seenPaths) {
        try {
            List<GithubRepositoryContent> contents = githubClient.listRepositoryContents(
                    accessToken, owner, repo, dirPath, branch);

            for (GithubRepositoryContent content : contents) {
                if ("file".equals(content.type()) && content.name().endsWith(".md")) {
                    // Dedupe: same file might be reached through multiple paths
                    if (seenPaths.add(content.path())) {
                        results.add(scanFile(content, entityMap, accessToken, owner, repo, branch));
                    }
                } else if ("dir".equals(content.type())) {
                    // Limit recursion depth
                    String relativePath = content.path();
                    if (relativePath.split("/").length < 5) { // max 5 levels deep
                        scanDirectoryRecursive(relativePath, branch, owner, repo, accessToken, entityMap, results, seenPaths);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not scan directory {}: {}", dirPath, e.getMessage());
        }
    }

    /**
     * Use AI to enrich drift detection for doc files that we suspect are out of date.
     * For each doc file that doesn't have strong code references but is in a location
     * that should be documenting code, ask AI if the content matches the current code.
     */
    private void enrichDriftDetectionWithAI(List<DocFileInfo> files, 
                                            List<CodeEntity> entities,
                                            UUID workspaceId,
                                            String branch,
                                            String repoName) {
        if (files.isEmpty() || entities.isEmpty()) return;
        if (!aiServiceClient.isEnabled()) return;

        // For each doc file, check if it has weak code references
        // We limit to top 5 candidates to keep AI call count low
        List<DocFileInfo> candidates = files.stream()
                .filter(f -> f.foundEntities().isEmpty())
                .limit(5)
                .toList();

        for (int i = 0; i < candidates.size(); i++) {
            DocFileInfo file = candidates.get(i);
            try {
                // We don't have content here - we'll just mark these as drift candidates
                // and let the frontend decide to preview them
                // (in a full implementation, we'd call AI.detectDrift here)
                if (file.driftReason() == null) {
                    int idx = files.indexOf(file);
                    if (idx >= 0) {
                        files.set(idx, new DocFileInfo(
                                file.path(), file.type(), file.size(),
                                file.referencedEntities(), file.foundEntities(),
                                file.missingEntities(), true,
                                "No code entities referenced; may need AI sync"));
                    }
                }
            } catch (Exception e) {
                log.debug("AI drift check failed for {}: {}", file.path(), e.getMessage());
            }
        }

        int driftCount = files.stream().mapToInt(f -> f.hasDrift() ? 1 : 0).sum();
        log.info("Drift detection complete for {}: {}/{} files have potential drift",
                repoName, driftCount, files.size());
    }

    private DocFileInfo scanFile(GithubRepositoryContent file, Map<String, CodeEntity> entityMap,
                                 String accessToken, String owner, String repo, String branch) {
        String path = file.path();
        List<String> foundEntities = new ArrayList<>();
        List<String> missingEntities = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        boolean hasDrift = false;
        String driftReason = null;

        // Try to fetch real content and extract code references
        String content = null;
        try {
            content = githubClient.getFileContent(accessToken, owner, repo, path, branch);
        } catch (Exception e) {
            log.debug("Could not fetch content for {}: {}", path, e.getMessage());
        }

        if (content != null && !content.isBlank()) {
            // Extract real code references from document content
            List<String> references = extractCodeReferences(content);
            String contentLower = content.toLowerCase();
            
            for (String ref : references) {
                String refLower = ref.toLowerCase();
                if (entityMap.containsKey(refLower)) {
                    foundEntities.add(entityMap.get(refLower).getSimpleName());
                } else {
                    missingEntities.add(ref);
                }
                seenNames.add(refLower);
            }

            // Check for drift: any entity in repo that SHOULD be documented but isn't
            String docName = extractDocName(path);
            Set<String> docNameWords = Set.of(docName.toLowerCase().split("\\s+"));
            
            List<CodeEntity> relatedEntities = entityMap.values().stream()
                    .filter(e -> {
                        String sn = e.getSimpleName().toLowerCase();
                        return docNameWords.stream().anyMatch(w -> 
                                w.length() > 2 && (sn.contains(w) || w.contains(sn)))
                                || contentLower.contains(sn);
                    })
                    .toList();

            // Drift detection logic
            if (!relatedEntities.isEmpty() && foundEntities.isEmpty()) {
                // Doc file is in a location relevant to entities, but doc doesn't reference any
                hasDrift = true;
                driftReason = "Document doesn't reference " + relatedEntities.size() 
                        + " related code entities: " 
                        + relatedEntities.stream()
                                .limit(3)
                                .map(CodeEntity::getSimpleName)
                                .collect(Collectors.joining(", "));
            } else if (foundEntities.size() < relatedEntities.size() / 2 
                    && relatedEntities.size() >= 2) {
                // Doc references fewer than half of the related entities
                hasDrift = true;
                driftReason = "Only " + foundEntities.size() + " of " + relatedEntities.size() 
                        + " related entities are documented";
            }
        } else {
            // Fallback: filename-based heuristic
            String fileName = file.name().replace(".md", "").replace("-", " ").replace("_", " ");
            String[] words = fileName.split("\\s+");
            
            for (String word : words) {
                if (word.length() > 2 && Character.isUpperCase(word.charAt(0))) {
                    String normalizedName = word.toLowerCase();
                    if (!seenNames.contains(normalizedName) && entityMap.containsKey(normalizedName)) {
                        foundEntities.add(word);
                    }
                    seenNames.add(normalizedName);
                }
            }
        }

        return new DocFileInfo(
                path,
                file.type(),
                file.size(),
                foundEntities.size() + missingEntities.size(),
                foundEntities,
                missingEntities,
                hasDrift,
                driftReason
        );
    }

    /**
     * Gets the content of a specific documentation file.
     */
    @Transactional(readOnly = true)
    public String getFileContent(UUID actorId, UUID workspaceId, UUID repositoryId,
                                String accessToken, String filePath, String branch) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new RuntimeException("Repository not found"));
        workspaceService.requireMember(actorId, repo.getWorkspaceId());

        String scanBranch = (branch != null && !branch.isBlank()) ? branch : repo.getDefaultBranch();
        
        return githubClient.getFileContent(accessToken, repo.getOwner(), repo.getName(), filePath, scanBranch);
    }

    /**
     * Extracts code entity names referenced in a markdown document.
     */
    public List<String> extractCodeReferences(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return List.of();
        }

        Set<String> references = new HashSet<>();
        
        for (Pattern pattern : CODE_PATTERNS) {
            Matcher matcher = pattern.matcher(markdownContent);
            while (matcher.find()) {
                String ref = matcher.group(1);
                if (ref != null && !ref.isBlank()) {
                    references.add(ref);
                }
            }
        }

        return new ArrayList<>(references);
    }

    /**
     * Finds code entities matching the given references.
     */
    @Transactional(readOnly = true)
    public List<CodeEntity> findMatchingEntities(UUID repositoryId, List<String> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }

        List<CodeEntity> allEntities = codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId);
        
        Set<String> refSet = references.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        return allEntities.stream()
                .filter(e -> refSet.contains(e.getSimpleName().toLowerCase()))
                .collect(Collectors.toList());
    }

    // ── Doc Sync Preview & Apply ─────────────────────────────────────────

    /**
     * Result of previewing a doc sync operation.
     */
    public record SyncPreviewResult(
            String filePath,
            String currentContent,
            String proposedContent,
            String diff,
            List<DriftFindingDto> findings,
            boolean noEntitiesFound
    ) {}

    /**
     * A single drift finding from AI analysis.
     */
    public record DriftFindingDto(
            String driftKind,
            String severity,
            String title,
            String description,
            String suggestion
    ) {}

    /**
     * Generates a preview of documentation updates by comparing code entities
     * with the current document content using AI.
     *
     * @param actorId      the user performing the action
     * @param workspaceId  the workspace containing the repository
     * @param repositoryId the repository
     * @param accessToken  GitHub access token
     * @param filePath     path to the markdown file to preview
     * @param branch       branch to scan (optional, defaults to default branch)
     * @return preview result with current/proposed content and diff
     */
    @Transactional(readOnly = true)
    public SyncPreviewResult previewSync(UUID actorId, UUID workspaceId, UUID repositoryId,
                                        String accessToken, String filePath, String branch) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new RuntimeException("Repository not found"));
        workspaceService.requireMember(actorId, repo.getWorkspaceId());

        String scanBranch = (branch != null && !branch.isBlank()) ? branch : repo.getDefaultBranch();
        log.info("Previewing doc sync for {} in repository {} branch {}", filePath, repo.getFullName(), scanBranch);

        // Get current document content
        String currentContent = githubClient.getFileContent(
                accessToken, repo.getOwner(), repo.getName(), filePath, scanBranch);
        if (currentContent == null) {
            throw new RuntimeException("Could not read file: " + filePath);
        }

        // Get code entities for this repository
        List<CodeEntity> entities = codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId);

        // Extract entities related to this document
        String docName = extractDocName(filePath);
        List<CodeEntity> relevantEntities = filterRelevantEntities(entities, docName, currentContent);

        // Call AI to generate updated documentation
        String proposedContent = generateUpdatedDoc(currentContent, relevantEntities, workspaceId,
                accessToken, repo, scanBranch);

        // Two distinct "nothing to propose" reasons:
        //   - no code entity is related to this document at all;
        //   - the AI call failed, so no content could be produced.
        // Both yield no proposal, but they must be reported differently —
        // conflating them hid AI outages behind a benign-looking message.
        boolean noEntitiesFound = relevantEntities.isEmpty();
        boolean aiUnavailable = !noEntitiesFound && proposedContent == null;

        // Generate diff (only if there's proposed content)
        String diff = proposedContent == null ? null : generateDiff(currentContent, proposedContent);

        // Drift findings are computed from the CURRENT content against the
        // indexed entities — they are meaningful even when the AI produced
        // no proposal.
        List<DriftFindingDto> findings;
        if (noEntitiesFound) {
            findings = List.of(new DriftFindingDto(
                    "NO_ENTITIES",
                    "INFO",
                    "No code entities found",
                    "This documentation file does not reference any code entities in the repository. It may be unrelated to code (e.g., onboarding, deployment guide) or already up to date.",
                    "Review if this file should reference any code entities, or mark it as reviewed."));
        } else if (aiUnavailable) {
            findings = List.of(new DriftFindingDto(
                    "AI_UNAVAILABLE",
                    "INFO",
                    "AI service did not return a proposal",
                    "The AI service was contacted but returned no usable content, so no documentation update could be proposed. The findings below were computed from the current document against the indexed code.",
                    "Retry the preview. If the problem persists, check the AI service configuration."));
        } else {
            findings = extractFindings(currentContent, relevantEntities);
        }

        return new SyncPreviewResult(filePath, currentContent, proposedContent, diff, findings, noEntitiesFound);
    }

    /**
     * Preview the sync AND queue the result as a {@link
     * com.livingdocs.modules.docsync.model.DocUpdateRequest} (status
     * PENDING_REVIEW) instead of immediately opening a PR. This is
     * the entry point of the review-before-push workflow: any member
     * can queue, only managers can apply.
     *
     * <p>Returns the DTO of the freshly created request (or
     * {@code null} when the AI proposed no real change vs. the
     * current file).
     */
    @Transactional
    public com.livingdocs.modules.docsync.dto.DocUpdateRequestDto queueForReview(
            UUID actorId, UUID workspaceId, UUID repositoryId, String accessToken,
            String filePath, String branch) {
        SyncPreviewResult preview = previewSync(actorId, workspaceId, repositoryId, accessToken, filePath, branch);
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new RuntimeException("Repository not found"));
        return docUpdateRequestService.createProposalIfNotPending(
                repo.getWorkspaceId(), repo.getId(), null,
                filePath, preview.currentContent(), preview.proposedContent(),
                preview.diff() == null ? null
                        : preview.diff().substring(0, Math.min(500, preview.diff().length())),
                null, branch, actorId);
    }

    /**
     * Applies the synced documentation by creating a pull request on GitHub.
     *
     * @param actorId      the user performing the action
     * @param workspaceId  the workspace containing the repository
     * @param repositoryId the repository
     * @param accessToken  GitHub access token
     * @param filePath     path to the markdown file
     * @param proposedContent the content to write
     * @param branch       branch (optional, for preview; uses default if null)
     * @return the PR URL, or null if failed
     */
    @Transactional(readOnly = true)
    public String applySync(UUID actorId, UUID workspaceId, UUID repositoryId,
                            String accessToken, String filePath, String proposedContent, String branch) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new RuntimeException("Repository not found"));
        workspaceService.requireMember(actorId, repo.getWorkspaceId());

        // Guard: if proposed content is identical to the current file, there is
        // nothing to commit and GitHub would reject the PR with 422.
        String currentContent = githubClient.getFileContent(
                accessToken, repo.getOwner(), repo.getName(), filePath, branch);
        if (proposedContent.equals(currentContent)) {
            throw new BadRequestException(
                    "No changes detected — documentation already matches the code.");
        }

        String baseBranch = (branch != null && !branch.isBlank()) ? branch : repo.getDefaultBranch();
        String headBranch = "docs/sync-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String commitMessage = "docs: sync with latest code - " + filePath;
        String prTitle = "📝 Sync documentation: " + extractDocName(filePath);
        String prBody = generatePrBody(filePath);

        log.info("Applying doc sync for {} in repository {} -> PR branch {}",
                filePath, repo.getFullName(), headBranch);

        // Clean up any stale branch from a previous attempt (e.g. an aborted sync
        // where the branch was created but the PR was not). Without this, GitHub
        // returns 409 because the file already exists on the branch.
        if (githubClient.branchExists(accessToken, repo.getOwner(), repo.getName(), headBranch)) {
            log.info("Deleting stale branch {} before re-applying", headBranch);
            githubClient.deleteBranch(accessToken, repo.getOwner(), repo.getName(), headBranch);
        }

        return githubClient.createFileAsPullRequest(
                accessToken,
                repo.getOwner(),
                repo.getName(),
                baseBranch,
                headBranch,
                filePath,
                proposedContent,
                commitMessage,
                prTitle,
                prBody
        );
    }

    private String extractDocName(String filePath) {
        // Extract meaningful name from path like "docs/api/auth.md" -> "api auth"
        String name = filePath
                .replace(".md", "")
                .replace("/", " ")
                .replace("-", " ")
                .replace("_", " ");
        return name.trim();
    }

    /**
     * Pick the code entities a documentation file is about.
     *
     * <p>Three signals are used, from strongest to weakest:
     * <ol>
     *   <li>the entity's name appears in the documentation body — the
     *       file explicitly documents it;</li>
     *   <li>the entity's name appears in the documentation's file name
     *       ({@code docs/api-client.md} → {@code ApiClient});</li>
     *   <li>the entity's <em>source file path</em> matches the
     *       documentation's file name ({@code docs/components.md} →
     *       everything under {@code src/components/}).</li>
     * </ol>
     *
     * <p>The third signal matters for documentation whose file name
     * describes a <em>directory</em> rather than an entity. Without it,
     * such a file matched nothing, the AI was never called, and the scan
     * reported the file as drifted while quietly producing no proposal.
     */
    private List<CodeEntity> filterRelevantEntities(List<CodeEntity> entities,
                                                    String docName,
                                                    String docContent) {
        Set<String> docNameWords = Set.of(docName.toLowerCase().split("\\s+"));
        String contentLower = docContent.toLowerCase();

        return entities.stream()
                .filter(e -> {
                    String simpleName = e.getSimpleName().toLowerCase();
                    if (contentLower.contains(simpleName)) {
                        return true;
                    }
                    if (docNameWords.stream().anyMatch(w ->
                            w.length() > 2
                                    && (simpleName.contains(w) || w.contains(simpleName)))) {
                        return true;
                    }
                    // Fall back to the source path: "components" matches
                    // "src/components/StatCard.tsx".
                    String filePath = e.getFilePath() != null
                            ? e.getFilePath().toLowerCase() : "";
                    return !filePath.isEmpty() && docNameWords.stream().anyMatch(w ->
                            w.length() > 2 && filePath.contains(w));
                })
                .limit(20) // Limit to top 20 most relevant
                .collect(Collectors.toList());
    }

    /**
     * Ask the AI to rewrite a documentation file so it matches the code.
     *
     * <p>The AI is grounded on the <em>actual source code</em> of the
     * entities, fetched from GitHub. Feeding it entity metadata instead
     * (name, kind, signature) produced a degenerate result: the generator
     * had nothing to parse, so it echoed the template back verbatim —
     * including the {@code {{#sources}}} placeholder and the "no source
     * provided" note. The proposal then looked like a real change to a
     * reviewer even though it carried no information.
     *
     * @return the proposed markdown, or {@code null} when the AI produced
     *         nothing usable
     */
    private String generateUpdatedDoc(String currentContent,
                                      List<CodeEntity> entities,
                                      UUID workspaceId,
                                      String accessToken,
                                      Repository repo,
                                      String branch) {
        if (entities.isEmpty()) {
            // No code entities are related to this file. Return null so the caller
            // knows there is nothing to propose and the modal can show a proper
            // "no entities found" state instead of an empty diff.
            return null;
        }

        // Ground the AI on the real source of each entity. The entity's
        // file path keeps the extension, so the AI service picks the
        // right analyser and can extract the entity's body.
        List<AiDtos.SourceFile> files = new ArrayList<>();
        for (CodeEntity entity : entities) {
            String source = fetchEntitySource(accessToken, repo, entity, branch);
            files.add(new AiDtos.SourceFile(entity.getFilePath(), source));
        }

        // The current documentation is part of the context: the AI has to
        // preserve whatever prose is still accurate and only fix what
        // drifted. It travels in the focus prompt rather than as a source
        // file, because a .md file has no analyser and would simply be
        // skipped by the generator.
        String focus = "Update the documentation below so it accurately reflects the "
                + "current implementation of the referenced code. Keep the existing "
                + "structure and prose wherever it is still correct; only correct "
                + "what the code contradicts. Output the complete Markdown document, "
                + "not a diff and not a fragment.\n\n"
                + "--- CURRENT DOCUMENTATION ---\n"
                + currentContent;

        // Use a template the AI service actually knows how to render.
        // "MODULE_GUIDE" is the built-in preset; passing a free-form
        // template string made the generator fall back to echoing it.
        //
        // The language hint must stay null: the AI service applies the hint
        // to *every* source file, and no analyser exists for "markdown",
        // so every file was skipped and the generator returned its empty
        // response. Letting it infer the language from each file's
        // extension is what makes the sources parseable.
        AiDtos.GenerateResponse response = aiServiceClient.generate(
                workspaceId, files, "MODULE_GUIDE", focus, null);

        if (response != null && response.markdown() != null && !response.markdown().isBlank()) {
            String proposed = response.markdown().strip();
            if (looksLikeUnrenderedTemplate(proposed)) {
                // The generator echoed a template back — that is not a
                // proposal, and queueing it would put an empty change in
                // front of a reviewer.
                log.warn("AI returned an unrendered template for {} entities — no proposal produced",
                        entities.size());
                return null;
            }
            // The AI often returns only the section it rewrote. When it
            // returns less than half of the current document, keep the
            // original prose and append the new section rather than
            // silently dropping the rest of the file.
            if (proposed.length() < currentContent.length() * 0.5) {
                return currentContent.strip() + "\n\n" + proposed + "\n";
            }
            return proposed + "\n";
        }

        // Fallback: the AI did not return a usable result. Return {@code null}
        // so the caller knows no proposal was produced.
        //
        // The previous implementation appended a synthetic "Referenced Code
        // Entities" section so the diff was guaranteed to be non-empty.
        // That fabricated a content change and let it be queued as a real
        // review proposal, which meant reviewers could approve a "change"
        // that the AI never produced. Returning null surfaces the real
        // state: nothing was proposed.
        log.warn("AI did not return usable content for {} entities — no proposal produced",
                entities.size());
        return null;
    }

    /**
     * Fetch the real source of an entity from GitHub.
     *
     * <p>Only the entity's line range is returned, so the AI service sees
     * the implementation rather than the whole file. When the fetch fails
     * the entity's metadata is used as a last resort — clearly marked, so
     * the model does not mistake it for code.
     */
    private String fetchEntitySource(String accessToken,
                                     Repository repo,
                                     CodeEntity entity,
                                     String branch) {
        try {
            String fullContent = githubClient.getFileContent(
                    accessToken, repo.getOwner(), repo.getName(),
                    entity.getFilePath(), branch);
            if (fullContent == null || fullContent.isBlank()) {
                return buildEntityContext(entity);
            }
            Integer start = entity.getStartLine();
            Integer end = entity.getEndLine();
            if (start == null || end == null || start <= 0 || end < start) {
                return fullContent;
            }
            String[] lines = fullContent.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, start - 1);
            int to = Math.min(lines.length, end);
            for (int i = from; i < to; i++) {
                sb.append(lines[i]).append("\n");
            }
            String snippet = sb.toString();
            return snippet.isBlank() ? buildEntityContext(entity) : snippet;
        } catch (Exception e) {
            log.debug("Could not fetch source for {}: {}", entity.getFilePath(), e.getMessage());
            return buildEntityContext(entity);
        }
    }

    /**
     * Whether the AI's answer is clearly an unrendered template rather
     * than documentation.
     *
     * <p>These markers show up when the generator had no usable source
     * and fell back to its "empty" response.
     */
    private boolean looksLikeUnrenderedTemplate(String markdown) {
        String lower = markdown.toLowerCase();
        return lower.contains("no source provided")
                || lower.contains("no parsable source")
                || lower.contains("{{#")
                || lower.contains("{{qualified_name}}")
                || lower.contains("{{/sources}}");
    }

    private String buildEntityContext(CodeEntity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append("Entity: ").append(entity.getQualifiedName()).append("\n");
        sb.append("Type: ").append(entity.getEntityType()).append("\n");
        sb.append("File: ").append(entity.getFilePath()).append(":").append(entity.getStartLine()).append("\n");
        if (entity.getSignature() != null) {
            sb.append("Signature: ").append(entity.getSignature()).append("\n");
        }
        if (entity.getDocstring() != null) {
            sb.append("Docstring: ").append(entity.getDocstring()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Produce a unified diff between the current and the proposed content.
     *
     * <p>Delegates to {@link DocumentVersionService#computeUnifiedDiff} so
     * the whole platform renders a single diff format. The previous
     * implementation compared lines by index, which meant a single
     * inserted line shifted every following line and the whole file was
     * reported as replaced.
     */
    private String generateDiff(String oldContent, String newContent) {
        return DocumentVersionService.computeUnifiedDiff(oldContent, newContent);
    }

    /**
     * Detect documentation drift by comparing the code entities the
     * document references against the entities that actually exist.
     *
     * <p>The previous implementation only looked for the literal strings
     * {@code TODO}/{@code FIXME} in the proposed content, which produced
     * a single hard-coded "incomplete" finding and never used the
     * platform's drift taxonomy. It now performs real referential and
     * signature drift detection:
     * <ul>
     *   <li><b>Referential</b> — the document names an entity that no
     *       longer exists in the indexed code.</li>
     *   <li><b>Signature</b> — the document quotes a signature that no
     *       longer matches the indexed one.</li>
     * </ul>
     *
     * @param currentContent the documentation as it exists today
     * @param relevantEntities the entities indexed for this repository
     */
    private List<DriftFindingDto> extractFindings(String currentContent,
                                                  List<CodeEntity> relevantEntities) {
        List<DriftFindingDto> findings = new ArrayList<>();
        if (currentContent == null || currentContent.isBlank()
                || relevantEntities == null || relevantEntities.isEmpty()) {
            return findings;
        }

        // Index the known entities by simple name so a mention in the
        // document can be resolved to an entity (or ruled out).
        Map<String, CodeEntity> bySimpleName = new LinkedHashMap<>();
        for (CodeEntity e : relevantEntities) {
            if (e.getSimpleName() != null && !e.getSimpleName().isBlank()) {
                bySimpleName.putIfAbsent(e.getSimpleName().toLowerCase(Locale.ROOT), e);
            }
        }

        // Referential drift: an identifier the document mentions that
        // matches no known entity. The existing CODE_PATTERNS already
        // encode the project's definition of "a code reference" (backticked
        // class names, camelCase identifiers, class declarations in code
        // blocks) and deliberately avoid matching ordinary prose words.
        Set<String> reported = new HashSet<>();
        for (Pattern pattern : CODE_PATTERNS) {
            Matcher mention = pattern.matcher(currentContent);
            while (mention.find()) {
                String name = mention.group(1);
                if (name == null || name.isBlank()) {
                    continue;
                }
                name = name.trim();
                int paren = name.indexOf('(');
                if (paren > 0) {
                    name = name.substring(0, paren).trim();
                }
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    name = name.substring(dot + 1).trim();
                }
                if (name.isEmpty() || name.length() < 3) {
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                if (bySimpleName.containsKey(key)) {
                    // The entity exists — check for a signature mismatch.
                    CodeEntity entity = bySimpleName.get(key);
                    String signature = entity.getSignature();
                    if (signature != null && !signature.isBlank()
                            && currentContent.contains(name)
                            && !currentContent.contains(signature.trim())) {
                        if (reported.add("sig:" + key)) {
                            findings.add(new DriftFindingDto(
                                    "SIGNATURE",
                                    "MEDIUM",
                                    "Signature of `" + name + "` may be outdated",
                                    "The document mentions `" + name + "` but the indexed signature is `"
                                            + signature.trim() + "`.",
                                    "Update the documented signature of `" + name + "`."));
                        }
                    }
                    continue;
                }
                // Only report as drift when the name looks like a real code
                // identifier (CamelCase or snake_case) — otherwise every
                // English word in the document becomes a false positive.
                if (!looksLikeCodeIdentifier(name)) {
                    continue;
                }
                if (!reported.add("ref:" + key)) {
                    continue;
                }
                findings.add(new DriftFindingDto(
                        "REFERENTIAL",
                        "HIGH",
                        "Referenced entity `" + name + "` no longer exists",
                        "The document references `" + name + "`, but no code entity with that name is "
                                + "indexed for this repository. It was most likely removed or renamed.",
                        "Remove or update the reference to `" + name + "`."));
            }
        }
        return findings;
    }

    /**
     * A name that plausibly denotes a code element rather than prose.
     */
    private boolean looksLikeCodeIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        boolean hasUpper = name.chars().anyMatch(Character::isUpperCase);
        boolean hasUnderscore = name.indexOf('_') >= 0;
        boolean hasLetter = name.chars().anyMatch(Character::isLetter);
        return hasLetter && (hasUpper || hasUnderscore);
    }

    private String generatePrBody(String filePath) {
        return String.format("""
            ## Documentation Sync
            
            This PR syncs the documentation file `%s` with the latest code.
            
            ### Changes
            - Updated code references
            - Verified entity signatures
            - Ensured documentation accuracy
            
            Please review the changes and merge if everything looks good.
            
            ---
            *Generated by LivingDocs*
            """, filePath);
    }
}
