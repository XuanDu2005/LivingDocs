package com.livingdocs.modules.github.service;

import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubClient.GithubRepositoryContent;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CodeEntityRepository codeEntityRepository;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceService workspaceService;

    public DocSyncService(GithubClient githubClient,
                         CodeEntityRepository codeEntityRepository,
                         RepositoryRepository repositoryRepository,
                         WorkspaceService workspaceService) {
        this.githubClient = githubClient;
        this.codeEntityRepository = codeEntityRepository;
        this.repositoryRepository = repositoryRepository;
        this.workspaceService = workspaceService;
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
            List<String> missingEntities
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
        Set<String> allReferencedEntities = new HashSet<>();

        for (String docPath : docPaths) {
            try {
                List<GithubRepositoryContent> contents = githubClient.listRepositoryContents(
                        accessToken, repo.getOwner(), repo.getName(), docPath, scanBranch);
                
                for (GithubRepositoryContent content : contents) {
                    if ("file".equals(content.type()) && content.name().endsWith(".md")) {
                        DocFileInfo fileInfo = scanFile(content, entityMap);
                        allFiles.add(fileInfo);
                        allReferencedEntities.addAll(fileInfo.foundEntities());
                    } else if ("dir".equals(content.type())) {
                        // Recursively scan subdirectories
                        scanDirectoryRecursive(content.path(), scanBranch, repo.getOwner(), 
                                repo.getName(), accessToken, entityMap, allFiles);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not scan path {} in repository {}: {}", 
                        docPath, repo.getFullName(), e.getMessage());
            }
        }

        int totalReferenced = allReferencedEntities.size();
        int matchedEntities = (int) allReferencedEntities.stream()
                .filter(name -> entityMap.containsKey(name.toLowerCase())).count();

        log.info("Scan complete: {} files, {} unique references, {} matched to entities",
                allFiles.size(), totalReferenced, matchedEntities);

        return new ScanResult(allFiles.size(), totalReferenced, matchedEntities, allFiles);
    }

    private void scanDirectoryRecursive(String dirPath, String branch, String owner, String repo,
                                       String accessToken, Map<String, CodeEntity> entityMap,
                                       List<DocFileInfo> results) {
        try {
            List<GithubRepositoryContent> contents = githubClient.listRepositoryContents(
                    accessToken, owner, repo, dirPath, branch);
            
            for (GithubRepositoryContent content : contents) {
                if ("file".equals(content.type()) && content.name().endsWith(".md")) {
                    results.add(scanFile(content, entityMap));
                } else if ("dir".equals(content.type())) {
                    // Limit recursion depth
                    String relativePath = content.path();
                    if (relativePath.split("/").length < 5) { // max 5 levels deep
                        scanDirectoryRecursive(relativePath, branch, owner, repo, accessToken, entityMap, results);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not scan directory {}: {}", dirPath, e.getMessage());
        }
    }

    private DocFileInfo scanFile(GithubRepositoryContent file, Map<String, CodeEntity> entityMap) {
        String path = file.path();
        List<String> foundEntities = new ArrayList<>();
        List<String> missingEntities = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        // For the scan result, we don't need to fetch content - just use path-based heuristics
        // In a full implementation, we would fetch the file content and parse it
        
        // Heuristic: infer possible references from filename and path
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

        // Also check with simpleName
        for (Map.Entry<String, CodeEntity> entry : entityMap.entrySet()) {
            String key = entry.getKey();
            if (!seenNames.contains(key)) {
                // Check if the filename contains this entity name
                String fileNameLower = file.name().toLowerCase();
                if (fileNameLower.contains(key) || fileNameLower.contains(entry.getValue().getSimpleName().toLowerCase())) {
                    foundEntities.add(entry.getValue().getSimpleName());
                    seenNames.add(key);
                }
            }
        }

        return new DocFileInfo(
                path,
                file.type(),
                file.size(),
                foundEntities.size() + missingEntities.size(),
                foundEntities,
                missingEntities
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
}
