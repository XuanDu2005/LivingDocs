package com.livingdocs.modules.code.service;

import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubClient.GithubRepositoryContent;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts code entities (classes, methods, functions) from a connected
 * GitHub repository by walking the source tree and parsing files with
 * simple regex-based heuristics. Persists results into
 * {@link CodeEntityRepository}.
 *
 * <p>This is intentionally lightweight: it covers Java, JavaScript, TypeScript
 * and Python. It runs synchronously and is best-effort; failures on a single
 * file are logged and skipped.</p>
 */
@Service
public class CodeEntityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(CodeEntityExtractionService.class);

    private static final List<String> DOC_PATHS = List.of("", "src", "src/main/java", "lib", "app");
    private static final List<String> SKIP_DIRS = List.of(
            "node_modules", "target", "build", "dist", ".git", ".idea", "out", "bin", "obj"
    );
    private static final int MAX_FILES = 200;

    private static final Pattern JAVA_CLASS =
            Pattern.compile("(?m)^\\s*(?:public|private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+([A-Z][A-Za-z0-9_]+)");
    private static final Pattern JAVA_INTERFACE =
            Pattern.compile("(?m)^\\s*(?:public|private|protected)?\\s*interface\\s+([A-Z][A-Za-z0-9_]+)");
    private static final Pattern JAVA_ENUM =
            Pattern.compile("(?m)^\\s*(?:public|private|protected)?\\s*enum\\s+([A-Z][A-Za-z0-9_]+)");
    private static final Pattern JAVA_METHOD =
            Pattern.compile("(?m)^\\s*(?:public|private|protected)\\s+(?:static\\s+)?(?:[A-Za-z0-9_<>\\[\\],\\s]+?)\\s+([a-z][A-Za-z0-9_]+)\\s*\\(");
    private static final Pattern JS_CLASS =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:default\\s+)?class\\s+([A-Z][A-Za-z0-9_]+)");
    private static final Pattern JS_FUNCTION =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern JS_ARROW =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:const|let|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*=\\s*(?:async\\s+)?\\(");
    private static final Pattern PY_CLASS =
            Pattern.compile("(?m)^class\\s+([A-Z][A-Za-z0-9_]+)");
    private static final Pattern PY_FUNCTION =
            Pattern.compile("(?m)^def\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern TS_CLASS =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+([A-Z][A-Za-z0-9_]+)");
    private static final Pattern TS_INTERFACE =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?interface\\s+([A-Z][A-Za-z0-9_]+)");

    private final CodeEntityRepository repository;
    private final RepositoryRepository githubRepoRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final GithubClient githubClient;
    private final GithubOAuthService oauthService;

    public CodeEntityExtractionService(CodeEntityRepository repository,
                                       RepositoryRepository githubRepoRepository,
                                       WorkspaceMemberRepository workspaceMemberRepository,
                                       GithubClient githubClient,
                                       GithubOAuthService oauthService) {
        this.repository = repository;
        this.githubRepoRepository = githubRepoRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.githubClient = githubClient;
        this.oauthService = oauthService;
    }

    /**
     * Walk the source tree of the repository, extract entities and persist
     * them. Returns the number of new entities stored.
     */
    @Transactional
    public int ingestFromRepository(UUID workspaceId, UUID repositoryId) {
        UUID userId = com.livingdocs.common.security.CurrentUser.requireId();
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId).isEmpty()) {
            throw new ForbiddenException("You are not a member of this workspace");
        }
        Repository repo = githubRepoRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }

        com.livingdocs.modules.github.model.GithubConnection conn =
                oauthService.requireForCurrentUser();
        String accessToken = conn.getAccessToken();
        String branch = repo.getDefaultBranch();
        if (branch == null || branch.isBlank()) branch = "main";

        log.info("Starting code entity extraction for {} @ {}", repo.getFullName(), branch);

        List<GithubRepositoryContent> targets = new ArrayList<>();
        for (String base : DOC_PATHS) {
            try {
                githubClient.listRepositoryContents(accessToken, repo.getOwner(), repo.getName(),
                        base, branch).forEach(c -> targets.add(c));
            } catch (Exception e) {
                log.debug("Could not list path {}: {}", base, e.getMessage());
            }
            if (targets.size() > MAX_FILES) break;
        }

        List<String> codeFiles = new ArrayList<>();
        collectCodeFiles(targets, accessToken, repo.getOwner(), repo.getName(), branch,
                codeFiles, 0);

        if (codeFiles.isEmpty()) {
            log.warn("No code files found in {} @ {}", repo.getFullName(), branch);
            return 0;
        }
        log.info("Found {} code file(s) to scan in {}", codeFiles.size(), repo.getFullName());

        // Clear old entities for this repo (best-effort refresh)
        repository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId)
                .forEach(e -> repository.deleteById(e.getId()));

        int stored = 0;
        int limit = Math.min(codeFiles.size(), MAX_FILES);
        for (int i = 0; i < limit; i++) {
            String path = codeFiles.get(i);
            try {
                String content = githubClient.getFileContent(accessToken, repo.getOwner(),
                        repo.getName(), path, branch);
                if (content == null || content.isBlank()) continue;
                String language = detectLanguage(path);
                if (language == null) continue;

                List<ParsedEntity> parsed = parse(content, language, path);
                for (ParsedEntity p : parsed) {
                    CodeEntity entity = new CodeEntity(
                            repositoryId,
                            branch,
                            language,
                            p.type,
                            p.qualifiedName,
                            p.simpleName,
                            path,
                            p.startLine,
                            p.endLine,
                            p.signature,
                            null,
                            "{}");
                    repository.save(entity);
                    stored++;
                }
            } catch (Exception e) {
                log.debug("Failed to parse {}: {}", path, e.getMessage());
            }
        }
        log.info("Extracted {} code entities from {}", stored, repo.getFullName());
        return stored;
    }

    private void collectCodeFiles(List<GithubRepositoryContent> entries, String token,
                                  String owner, String repo, String branch,
                                  List<String> out, int depth) {
        if (depth > 4 || out.size() > MAX_FILES) return;
        for (GithubRepositoryContent entry : entries) {
            if (out.size() > MAX_FILES) return;
            String name = entry.name();
            if (SKIP_DIRS.contains(name)) continue;
            if ("file".equals(entry.type())) {
                if (isCodeFile(name)) {
                    out.add(entry.path());
                }
            } else if ("dir".equals(entry.type())) {
                try {
                    List<GithubRepositoryContent> children = githubClient.listRepositoryContents(
                            token, owner, repo, entry.path(), branch);
                    collectCodeFiles(children, token, owner, repo, branch, out, depth + 1);
                } catch (Exception e) {
                    log.debug("Could not descend into {}: {}", entry.path(), e.getMessage());
                }
            }
        }
    }

    private boolean isCodeFile(String name) {
        return name.endsWith(".java") || name.endsWith(".js")
                || name.endsWith(".ts") || name.endsWith(".tsx")
                || name.endsWith(".py");
    }

    private String detectLanguage(String path) {
        if (path.endsWith(".java")) return "java";
        if (path.endsWith(".ts") || path.endsWith(".tsx")) return "typescript";
        if (path.endsWith(".js")) return "javascript";
        if (path.endsWith(".py")) return "python";
        return null;
    }

    private List<ParsedEntity> parse(String content, String language, String path) {
        List<ParsedEntity> out = new ArrayList<>();
        switch (language) {
            case "java" -> {
                findAll(JAVA_CLASS, content, "class", path, out);
                findAll(JAVA_INTERFACE, content, "interface", path, out);
                findAll(JAVA_ENUM, content, "enum", path, out);
                findAll(JAVA_METHOD, content, "method", path, out);
            }
            case "javascript" -> {
                findAll(JS_CLASS, content, "class", path, out);
                findAll(JS_FUNCTION, content, "function", path, out);
                findAll(JS_ARROW, content, "function", path, out);
            }
            case "typescript" -> {
                findAll(TS_CLASS, content, "class", path, out);
                findAll(TS_INTERFACE, content, "interface", path, out);
                findAll(JS_FUNCTION, content, "function", path, out);
                findAll(JS_ARROW, content, "function", path, out);
            }
            case "python" -> {
                findAll(PY_CLASS, content, "class", path, out);
                findAll(PY_FUNCTION, content, "function", path, out);
            }
        }
        return out;
    }

    private void findAll(Pattern pattern, String content, String type, String path,
                         List<ParsedEntity> out) {
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            if (name == null || name.isBlank()) continue;
            int line = lineNumberAt(content, m.start());
            out.add(new ParsedEntity(
                    type,
                    name,
                    path + "::" + name,
                    line,
                    line,
                    m.group()
            ));
        }
    }

    private int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private record ParsedEntity(String type, String simpleName, String qualifiedName,
                                int startLine, int endLine, String signature) {}
}
