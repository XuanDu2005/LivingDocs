package com.livingdocs.modules.code.service;

import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubClient.GithubRepositoryContent;
import com.livingdocs.modules.github.model.Commit;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
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
    private final CodeEntityService codeEntityService;
    private final RepositoryRepository githubRepoRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final CommitRepository commitRepository;
    private final GithubClient githubClient;
    private final GithubOAuthService oauthService;

    public CodeEntityExtractionService(CodeEntityRepository repository,
                                       CodeEntityService codeEntityService,
                                       RepositoryRepository githubRepoRepository,
                                       WorkspaceMemberRepository workspaceMemberRepository,
                                       CommitRepository commitRepository,
                                       GithubClient githubClient,
                                       GithubOAuthService oauthService) {
        this.repository = repository;
        this.codeEntityService = codeEntityService;
        this.githubRepoRepository = githubRepoRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.commitRepository = commitRepository;
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

        // The entity's `commit_sha` must be a real commit hash: the grounding
        // evidence shown to reviewers cites it, and it is part of the
        // entity's unique key. Storing the branch name here would make
        // every entity claim the same (non-existent) commit. Fall back to
        // the branch name only when no commit has been ingested yet.
        String commitSha = resolveHeadCommitSha(repositoryId, branch);

        log.info("Starting code entity extraction for {} @ {} (head {})",
                repo.getFullName(), branch, commitSha);

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
                    // Skip if already exists to avoid unique constraint violations
                    if (repository.existsByRepositoryIdAndCommitShaAndQualifiedNameAndFilePathAndStartLine(
                            repositoryId, commitSha, p.qualifiedName, path, p.startLine)) {
                        log.debug("Skipping duplicate entity: {} @ line {}", p.qualifiedName, p.startLine);
                        continue;
                    }
                    CodeEntity entity = new CodeEntity(
                            repositoryId,
                            commitSha,
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

        // Drop the cached "all entities for this repo" view so the next
        // reader sees the freshly extracted set instead of stale data.
        codeEntityService.invalidateRepository(repositoryId);

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
            // The declaration may span multiple lines, and for classes /
            // methods the body extends until the matching closing brace.
            // Recording a single-line range would hand the AI a one-line
            // stub instead of the real source it is supposed to document.
            int endLine = line + countDeclarationLines(content, m.start());
            out.add(new ParsedEntity(
                    type,
                    name,
                    path + "::" + name,
                    line,
                    endLine,
                    m.group()
            ));
        }
    }

    /**
     * Determine how many lines a declaration occupies.
     *
     * <p>If the declaration is followed by a brace-delimited body, the range
     * extends to the matching closing brace. Otherwise it extends to the end
     * of the declaration statement (semicolon or balanced parentheses).
     */
    private int countDeclarationLines(String content, int startOffset) {
        int openBrace = content.indexOf('{', startOffset);
        int openParen = content.indexOf('(', startOffset);

        // A body only counts when it appears after the parameter list, so a
        // brace inside the signature (e.g. an array initializer) is not
        // mistaken for the start of the body.
        if (openParen >= 0 && (openBrace < 0 || openBrace > openParen)) {
            int closeParen = matchForward(content, openParen, '(', ')');
            int braceAfterParams = content.indexOf('{', closeParen < 0 ? openParen : closeParen);
            if (braceAfterParams >= 0) {
                int closeBrace = matchForward(content, braceAfterParams, '{', '}');
                return lineNumberAt(content, closeBrace < 0 ? braceAfterParams : closeBrace) - lineNumberAt(content, startOffset);
            }
            int semi = content.indexOf(';', openParen);
            if (semi >= 0) {
                return lineNumberAt(content, semi) - lineNumberAt(content, startOffset);
            }
        }

        if (openBrace >= 0 && (openParen < 0 || openBrace < openParen)) {
            int closeBrace = matchForward(content, openBrace, '{', '}');
            return lineNumberAt(content, closeBrace < 0 ? openBrace : closeBrace) - lineNumberAt(content, startOffset);
        }

        // Fallback: the declaration is a single line.
        return 0;
    }

    /**
     * Find the offset of the bracket that matches the one at {@code openOffset}.
     * Returns {@code -1} when the source is unbalanced.
     */
    private int matchForward(String content, int openOffset, char open, char close) {
        int depth = 0;
        for (int i = openOffset; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Resolve the SHA of the most recent commit ingested for the given
     * branch. Falls back to the branch name when the repository has no
     * ingested commits yet (e.g. a fresh connection).
     */
    private String resolveHeadCommitSha(UUID repositoryId, String branch) {
        List<Commit> commits = commitRepository
                .findAllByRepositoryIdOrderByAuthoredAtDesc(repositoryId, Pageable.ofSize(50));
        for (Commit c : commits) {
            if (branch == null || branch.isBlank()
                    || branch.equals(c.getBranch())
                    || c.getBranch() == null) {
                return c.getSha();
            }
        }
        return branch == null || branch.isBlank() ? "unknown" : branch;
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
