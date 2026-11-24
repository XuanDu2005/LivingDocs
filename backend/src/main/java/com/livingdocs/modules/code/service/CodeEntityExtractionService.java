package com.livingdocs.modules.code.service;

import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.RepositoryArchive;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Directory names that never hold first-party source code.
     *
     * <p>They are skipped during the tree walk so a vendored dependency
     * tree cannot flood the ingest.
     */
    private static final List<String> SKIP_DIRS = List.of(
            "node_modules", "target", "build", "dist", ".git", ".idea", "out", "bin", "obj",
            "vendor", ".next", ".venv", "venv", "__pycache__", ".mvn", ".gradle", "coverage"
    );

    /**
     * Upper bound on how many files are parsed per ingest.
     *
     * <p>Before the archive download, every file cost one GitHub request,
     * so this bound kept the ingest from taking hours. The archive makes
     * the download a single request, so the bound now only guards the
     * parsing work. It is still worth having: a repository with tens of
     * thousands of source files would otherwise produce an entity set
     * that is expensive to query and to feed to the AI.
     *
     * <p>When the bound bites, it is reported — a silently half-indexed
     * repository looks exactly like a fully indexed one.
     */
    private static final int MAX_FILES = 2000;

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
    // A TypeScript generic parameter list may sit between the name and the
    // argument list: `function useDebounce<T>(value: T)`. Without this the
    // whole declaration is invisible to the extractor, so a file written
    // with generics contributes no entities at all — the document it
    // belongs to then reports "no code entities found" and no proposal
    // is ever produced for it.
    //
    // The generic list is matched separately from the argument list so
    // that a parameter default value containing a comparison
    // (`(a: number = a < b ? 1 : 2)`) cannot be mistaken for one.
    private static final Pattern JS_FUNCTION =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*(?:<[^<>()]*>)?\\s*\\(");
    private static final Pattern JS_ARROW =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:const|let|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*(?::[^=]+)?=\\s*(?:async\\s+)?(?:<[^<>()]*>\\s*)?\\(");
    private static final Pattern PY_CLASS =
            Pattern.compile("(?m)^\\s*class\\s+([A-Z][A-Za-z0-9_]+)");
    // A method is indented inside its class body, so the pattern has to
    // allow leading whitespace. Anchored on the name group it records
    // the right line — see {@link #findAll}.
    private static final Pattern PY_FUNCTION =
            Pattern.compile("(?m)^\\s*def\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern TS_CLASS =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+([A-Z][A-Za-z0-9_]+)");
    private static final Pattern TS_INTERFACE =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?interface\\s+([A-Z][A-Za-z0-9_]+)");
    // The TypeScript counterparts of the JS patterns above. They are kept
    // separate from the JavaScript ones because TypeScript allows a
    // return-type annotation and an `async` modifier in front of the
    // parameter list, which the plain JavaScript pattern does not
    // tolerate.
    private static final Pattern TS_FUNCTION =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*(?:<[^<>()]*>)?\\s*\\(");
    // A generic arrow puts its parameter list *after* the `=`
    // (`const identity = <T,>(value: T) => value`), so the generic is
    // matched there and not before the `=`.
    private static final Pattern TS_ARROW =
            Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:const|let|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*(?::[^=]+)?=\\s*(?:async\\s+)?(?:<[^<>()]*>\\s*)?\\(");
    // A method of a class body. The AI service recognises these (see its
    // `method_re`), so the backend must too — otherwise the two sides
    // disagree about what an entity is, and a method that the backend
    // indexed yields no source for the model.
    //
    // The pattern mirrors the AI service's `method_re` exactly: the name
    // is captured on its own, the generic list is matched separately, and
    // a return-type annotation is required. That last requirement is what
    // keeps the control-flow statements out — `if (x) {` and
    // `for (const x of y) {` also look like an indented name followed by
    // an opening parenthesis — and what keeps constructors out, which the
    // AI service does not treat as entities either.
    private static final Pattern TS_METHOD =
            Pattern.compile("(?m)^\\s+(?:public|private|protected|static|readonly|async)?\\s*"
                    + "([a-zA-Z_][A-Za-z0-9_]*)\\s*(?:<[^<>()]*>)?\\s*\\([^)]*\\)"
                    + "\\s*:\\s*[^;{]+\\s*\\{");

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

        // The credential to read the repository with is the one of the
        // user who connected it — that is the account whose scopes cover
        // the repository. The calling user is only the fallback: a
        // colleague re-indexing a repository they did not connect must
        // not fail because their own GitHub account cannot see it.
        //
        // Previously the *current* user's token was used unconditionally,
        // which meant the ingest of a repository failed whenever the
        // caller was not the one who connected it.
        String accessToken = resolveAccessToken(repo, userId);
        if (accessToken == null || accessToken.isBlank()) {
            throw new com.livingdocs.common.exception.ForbiddenException(
                    "No GitHub connection is available for this repository. "
                            + "Re-authorize the GitHub app and try again.");
        }
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

        // The whole repository is fetched in a single request. Reading it
        // through the contents API instead would cost one request per
        // directory — hundreds of them for a monorepo — and any
        // traversal with a depth limit silently drops the files it never
        // reached. That is exactly what happened before this change: the
        // crawl stopped after four levels, so `backend/src/main/java/com/…`
        // (depth nine and deeper) was invisible and the repository
        // indexed no Java source at all.
        byte[] archive = githubClient.downloadRepositoryArchive(
                accessToken, repo.getOwner(), repo.getName(), branch);

        if (archive == null || archive.length == 0) {
            log.warn("Could not download the repository archive for {} @ {}",
                    repo.getFullName(), branch);
            return 0;
        }

        Map<String, byte[]> sources;
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(archive)) {
            sources = RepositoryArchive.read(in, this::isIndexableSource);
        } catch (Exception e) {
            log.warn("Could not read the repository archive of {} @ {}: {}",
                    repo.getFullName(), branch, e.getMessage());
            return 0;
        }

        if (sources.isEmpty()) {
            log.warn("No code files found in {} @ {}", repo.getFullName(), branch);
            return 0;
        }
        if (sources.size() > MAX_FILES) {
            log.warn("{} has {} indexable source file(s); only the first {} are indexed",
                    repo.getFullName(), sources.size(), MAX_FILES);
            Map<String, byte[]> capped = new LinkedHashMap<>();
            int kept = 0;
            for (Map.Entry<String, byte[]> source : sources.entrySet()) {
                if (kept++ >= MAX_FILES) break;
                capped.put(source.getKey(), source.getValue());
            }
            sources = capped;
        }
        log.info("Found {} code file(s) to scan in {}", sources.size(), repo.getFullName());

        // Clear old entities for this repo (best-effort refresh)
        repository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId)
                .forEach(e -> repository.deleteById(e.getId()));

        int stored = 0;
        for (Map.Entry<String, byte[]> entry : sources.entrySet()) {
            String path = entry.getKey();
            try {
                String content = new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                if (content.isBlank()) continue;
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

    /**
     * Whether a file of the repository archive should be indexed.
     *
     * <p>The archive contains everything the repository holds —
     * documentation, lock files, assets, vendored dependencies. Only
     * first-party source code is indexed, and only outside the
     * directories that hold generated or third-party code.
     */
    private boolean isIndexableSource(String path) {
        if (path == null || path.isBlank()) return false;
        if (isSkippedPath(path)) return false;
        return isCodeFile(path);
    }

    /**
     * Whether the path lies inside a directory that never holds
     * first-party source code.
     */
    private boolean isSkippedPath(String path) {
        for (String segment : path.split("/")) {
            if (SKIP_DIRS.contains(segment)) return true;
        }
        return false;
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

    /**
     * Extract the entities of a single file.
     *
     * <p>Package-private rather than private so the extraction rules can
     * be tested directly — the patterns encode real-world TypeScript
     * shapes (generics, arrow functions, JSX) that are easy to break
     * silently, and a broken pattern shows up in the product only as
     * "this document has no code entities", which is a very indirect
     * signal.
     */
    List<ParsedEntity> parse(String content, String language, String path) {
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
                findAll(TS_FUNCTION, content, "function", path, out);
                findAll(TS_ARROW, content, "function", path, out);
                findAll(TS_METHOD, content, "method", path, out);
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
            // Anchor the line number on the captured name rather than on
            // the start of the whole match. The patterns begin with
            // `^\s*`, and in MULTILINE mode `\s` also matches the newline
            // that ended the previous line — so the match starts one line
            // *before* the declaration and every entity was recorded with
            // a line number that was off by one. The name group is the
            // first character that is guaranteed to sit on the
            // declaration's own line.
            int line = lineNumberAt(content, m.start(1));
            // The declaration may span multiple lines, and for classes /
            // methods the body extends until the matching closing brace.
            // Recording a single-line range would hand the AI a one-line
            // stub instead of the real source it is supposed to document.
            int endLine = line + countDeclarationLines(content, m.start(1));
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
     * The GitHub credential to read the repository with.
     *
     * <p>The credential of the user who connected the repository is
     * preferred — that is the account whose scopes cover the repository.
     * The requesting user is the fallback.
     */
    private String resolveAccessToken(Repository repo, UUID requestedBy) {
        java.util.Optional<com.livingdocs.modules.github.model.GithubConnection> connection =
                oauthService.findForUser(repo.getConnectedBy());
        if (connection.isPresent()) {
            String token = connection.get().getAccessToken();
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return oauthService.findForUser(requestedBy)
                .map(c -> c.getAccessToken())
                .orElse(null);
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

    /**
     * One extracted entity.
     *
     * <p>Package-private so a test can assert on the extracted shape
     * without going through the database.
     */
    record ParsedEntity(String type, String simpleName, String qualifiedName,
                        int startLine, int endLine, String signature) {}
}
