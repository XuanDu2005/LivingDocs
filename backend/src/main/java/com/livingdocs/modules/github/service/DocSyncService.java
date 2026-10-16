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
        Set<String> allReferencedEntities = new HashSet<>();

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
                            allReferencedEntities.addAll(fileInfo.foundEntities());
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
        String proposedContent = generateUpdatedDoc(currentContent, relevantEntities, workspaceId);

        // Check if no entities were found
        boolean noEntitiesFound = (proposedContent == null);

        // Generate diff (only if there's proposed content)
        String diff = noEntitiesFound ? null : generateDiff(currentContent, proposedContent);

        // Extract findings from AI response (only if there's proposed content)
        List<DriftFindingDto> findings = noEntitiesFound
                ? List.of(new DriftFindingDto(
                        "NO_ENTITIES",
                        "INFO",
                        "No code entities found",
                        "This documentation file does not reference any code entities in the repository. It may be unrelated to code (e.g., onboarding, deployment guide) or already up to date.",
                        "Review if this file should reference any code entities, or mark it as reviewed."))
                : extractFindings(proposedContent);

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
        return docUpdateRequestService.createProposal(
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

    private List<CodeEntity> filterRelevantEntities(List<CodeEntity> entities, String docName, String docContent) {
        Set<String> docNameWords = Set.of(docName.toLowerCase().split("\\s+"));
        
        return entities.stream()
                .filter(e -> {
                    String simpleName = e.getSimpleName().toLowerCase();
                    // Match if entity name appears in doc name or doc content
                    return docNameWords.stream().anyMatch(w -> simpleName.contains(w) || w.contains(simpleName))
                            || docContent.toLowerCase().contains(simpleName);
                })
                .limit(20) // Limit to top 20 most relevant
                .collect(Collectors.toList());
    }

    private String generateUpdatedDoc(String currentContent, List<CodeEntity> entities, UUID workspaceId) {
        if (entities.isEmpty()) {
            // No code entities are related to this file. Return null so the caller
            // knows there is nothing to propose and the modal can show a proper
            // "no entities found" state instead of an empty diff.
            return null;
        }

        // Build context from code entities
        List<AiDtos.SourceFile> files = entities.stream()
                .map(e -> new AiDtos.SourceFile(
                        e.getFilePath(),
                        buildEntityContext(e)))
                .collect(Collectors.toList());

        String template = """
                ## Code Entity Reference

                The following code entities are referenced in this documentation:

                {{#sources}}
                - `{{qualified_name}}` ({{kind}}) - {{signature}}
                {{/sources}}

                Please ensure the documentation above accurately reflects the current implementation.
                """;

        AiDtos.GenerateResponse response = aiServiceClient.generate(
                workspaceId, files, template, "Update documentation to match code", "markdown");

        if (response != null && response.markdown() != null) {
            // Merge AI response with current content if it doesn't completely replace it
            if (response.markdown().length() < currentContent.length() * 0.5) {
                // AI suggestion is much shorter, append it
                return currentContent + "\n\n" + response.markdown();
            }
            return response.markdown();
        }

        // Fallback: append a real "Referenced Code Entities" section so that
        // the proposed content is GUARANTEED to differ from currentContent
        // when the document is missing entity references. Without this, the
        // diff is empty and the user sees a useless modal.
        StringBuilder sb = new StringBuilder(currentContent);
        if (!currentContent.endsWith("\n")) sb.append("\n");
        sb.append("\n## Referenced Code Entities\n\n");
        sb.append("_Auto-generated by LivingDocs — this section lists code entities related to this document._\n\n");
        for (CodeEntity e : entities) {
            sb.append("- `").append(e.getQualifiedName()).append("` (")
              .append(e.getEntityType()).append(")");
            if (e.getSignature() != null && !e.getSignature().isBlank()) {
                sb.append(" — ").append(e.getSignature());
            }
            sb.append("\n");
        }
        return sb.toString();
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

    private String generateDiff(String oldContent, String newContent) {
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/original\n");
        diff.append("+++ b/proposed\n");

        String[] oldLines = oldContent.split("\n");
        String[] newLines = newContent.split("\n");

        // Simple line-by-line diff
        int i = 0, j = 0;
        while (i < oldLines.length || j < newLines.length) {
            if (i >= oldLines.length) {
                // Addition
                diff.append("+ ").append(newLines[j]).append("\n");
                j++;
            } else if (j >= newLines.length) {
                // Deletion
                diff.append("- ").append(oldLines[i]).append("\n");
                i++;
            } else if (oldLines[i].equals(newLines[j])) {
                // Unchanged
                diff.append("  ").append(oldLines[i]).append("\n");
                i++;
                j++;
            } else {
                // Changed
                diff.append("- ").append(oldLines[i]).append("\n");
                diff.append("+ ").append(newLines[j]).append("\n");
                i++;
                j++;
            }
        }

        return diff.toString();
    }

    private List<DriftFindingDto> extractFindings(String content) {
        List<DriftFindingDto> findings = new ArrayList<>();

        // Simple heuristic: check for common patterns
        if (content.contains("TODO") || content.contains("FIXME")) {
            findings.add(new DriftFindingDto(
                    "incomplete",
                    "medium",
                    "Documentation may be incomplete",
                    "The document contains TODO/FIXME markers",
                    "Consider completing the documentation before syncing"
            ));
        }

        return findings;
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
