package com.livingdocs.modules.ai.analysis;

import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubClient.GithubRepositoryContent;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.service.GithubOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks a repository on GitHub and builds a {@link RepositorySnapshot}.
 *
 * <p>The scan is deliberately <b>bounded</b>. A repository can hold
 * tens of thousands of files, and a snapshot of all of them would blow
 * the LLM context window long before the model had read anything
 * useful. The scan therefore collects three things:
 * <ul>
 *   <li>the <em>path</em> of every file — cheap, and it is what makes
 *       the layout visible to the model;</li>
 *   <li>the <em>content</em> of the dependency manifests and the
 *       entry points — these are what make the "how do I run this"
 *       and "what does this depend on" answers possible;</li>
 *   <li>the content of a sample of the main source files — this is
 *       what grounds the architecture and API descriptions.</li>
 * </ul>
 *
 * <p>The GitHub Contents API is walked directory by directory. The
 * walk is capped in both depth and file count so a pathological
 * repository (a vendored {@code node_modules}, a monorepo with a
 * thousand packages) cannot turn one click into ten thousand HTTP
 * calls.
 */
@Service
public class RepositorySnapshotScanner {

    private static final Logger log = LoggerFactory.getLogger(RepositorySnapshotScanner.class);

    /** How deep the directory walk goes. */
    private static final int MAX_DEPTH = 4;

    /** How many file paths the snapshot carries at most. */
    private static final int MAX_TREE_ENTRIES = 1500;

    /** How many directories are visited at most. */
    private static final int MAX_DIRECTORIES = 300;

    /** How many source files are fetched in full. */
    private static final int MAX_KEY_FILES = 25;

    /** How many characters of a single file are handed to the model. */
    private static final int MAX_FILE_CHARS = 12000;

    /** Directories that never carry documentation-relevant signal. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "dist", "build", "target", "out", "bin", "obj",
            ".git", ".idea", ".vscode", ".venv", "venv", "__pycache__",
            ".mvn", ".gradle", "coverage", ".next", ".nuxt", "vendor");

    /** Files whose content decides how the project is built and run. */
    private static final List<String> MANIFEST_NAMES = List.of(
            "package.json", "pom.xml", "build.gradle", "build.gradle.kts",
            "requirements.txt", "pyproject.toml", "setup.py", "go.mod",
            "Cargo.toml", "composer.json", "Gemfile", "pubspec.yaml",
            "docker-compose.yml", "docker-compose.yaml", "Dockerfile",
            "Makefile", "settings.gradle", "settings.gradle.kts",
            "application.yml", "application.yaml", "application.properties");

    /** Files that usually hold the "how to start" instructions. */
    private static final List<String> ENTRY_POINT_NAMES = List.of(
            "README.md", "readme.md", "README.rst", "README",
            "CONTRIBUTING.md", "docker-compose.yml", "docker-compose.yaml",
            "Dockerfile", "Makefile", "main.py", "app.py", "index.ts",
            "index.js", "main.ts", "main.js", "server.ts", "server.js",
            "Application.java", "main.go", "cmd");

    private final GithubClient githubClient;
    private final GithubOAuthService githubOAuthService;

    public RepositorySnapshotScanner(GithubClient githubClient,
                                     GithubOAuthService githubOAuthService) {
        this.githubClient = githubClient;
        this.githubOAuthService = githubOAuthService;
    }

    /**
     * Build the snapshot of a repository.
     *
     * @param repository the repository to scan
     * @param token      the GitHub access token to read it with
     */
    public RepositorySnapshot scan(Repository repository, String token) {
        String owner = repository.getOwner();
        String name = repository.getName();
        String branch = repository.getDefaultBranch();

        Walk walk = new Walk(token, owner, name, branch);
        walk.collect();

        List<String> tree = new ArrayList<>(walk.paths);
        Map<String, Integer> languages = countByLanguage(walk.paths);
        Map<String, Integer> directories = countTopLevelDirectories(walk.paths);

        Map<String, String> manifests = new LinkedHashMap<>();
        for (String path : walk.manifestPaths) {
            String content = walk.fetch(path);
            if (content != null) {
                manifests.put(path, truncate(content, MAX_FILE_CHARS));
            }
        }

        List<String> entryPoints = new ArrayList<>();
        for (String path : walk.entryPointPaths) {
            if (!entryPoints.contains(path)) {
                entryPoints.add(path);
            }
        }

        List<RepositorySnapshot.KeyFile> keyFiles = new ArrayList<>();
        for (String path : walk.keyFilePaths()) {
            String content = walk.fetch(path);
            if (content == null || content.isBlank()) {
                continue;
            }
            keyFiles.add(new RepositorySnapshot.KeyFile(
                    path, truncate(content, MAX_FILE_CHARS), languageOf(path)));
        }

        log.info("Repository snapshot of {}/{}: {} files, {} manifests, {} key files",
                owner, name, walk.paths.size(), manifests.size(), keyFiles.size());

        return new RepositorySnapshot(
                repository.getFullName(),
                repository.getDescription(),
                branch,
                tree,
                languages,
                directories,
                manifests,
                entryPoints,
                keyFiles,
                walk.paths.size(),
                walk.truncated);
    }

    // -----------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------

    /**
     * The mutable state of one repository walk.
     *
     * <p>It is a private class rather than a set of local variables
     * because the walk is recursive and the collected paths have to be
     * readable from the helper methods that pick the interesting files.
     */
    private final class Walk {
        private final String token;
        private final String owner;
        private final String repo;
        private final String branch;

        private final List<String> paths = new ArrayList<>();
        private final Set<String> seen = new LinkedHashSet<>();
        private final Set<String> manifestPaths = new LinkedHashSet<>();
        private final Set<String> entryPointPaths = new LinkedHashSet<>();
        private final Map<String, String> contentCache = new LinkedHashMap<>();
        private final List<String> sourcePaths = new ArrayList<>();
        private boolean truncated;
        private int directoriesVisited;

        Walk(String token, String owner, String repo, String branch) {
            this.token = token;
            this.owner = owner;
            this.repo = repo;
            this.branch = branch;
        }

        void collect() {
            walk("", 0);
        }

        private void walk(String path, int depth) {
            if (depth > MAX_DEPTH || directoriesVisited >= MAX_DIRECTORIES) {
                truncated = true;
                return;
            }
            directoriesVisited++;

            List<GithubRepositoryContent> contents;
            try {
                contents = githubClient.listRepositoryContents(token, owner, repo, path, branch);
            } catch (Exception e) {
                log.warn("Could not list {}/{}/{}: {}", owner, repo, path, e.getMessage());
                return;
            }
            if (contents == null || contents.isEmpty()) {
                return;
            }

            for (GithubRepositoryContent entry : contents) {
                String entryPath = entry.path() == null ? entry.name() : entry.path();
                if (entryPath == null) {
                    continue;
                }
                if ("dir".equals(entry.type())) {
                    String dirName = entry.name() == null ? "" : entry.name();
                    if (IGNORED_DIRECTORIES.contains(dirName)
                            || dirName.startsWith(".")) {
                        continue;
                    }
                    walk(entryPath, depth + 1);
                    continue;
                }

                if (!seen.add(entryPath)) {
                    continue;
                }
                if (paths.size() >= MAX_TREE_ENTRIES) {
                    truncated = true;
                    continue;
                }
                paths.add(entryPath);

                String fileName = entry.name() == null ? "" : entry.name();
                if (MANIFEST_NAMES.contains(fileName) || MANIFEST_NAMES.contains(entryPath)) {
                    manifestPaths.add(entryPath);
                }
                if (isEntryPoint(entryPath, fileName)) {
                    entryPointPaths.add(entryPath);
                }
                if (isSourceFile(entryPath)) {
                    sourcePaths.add(entryPath);
                }
            }
        }

        /**
         * The source files worth reading in full.
         *
         * <p>Files that sit at the top of the tree are preferred over
         * deeply nested ones, because a top-level file is usually the
         * one that shows how the pieces fit together.
         */
        List<String> keyFilePaths() {
            List<String> sorted = new ArrayList<>(sourcePaths);
            sorted.sort((a, b) -> {
                int depthCompare = Integer.compare(depthOf(a), depthOf(b));
                if (depthCompare != 0) {
                    return depthCompare;
                }
                return a.compareTo(b);
            });
            return sorted.subList(0, Math.min(MAX_KEY_FILES, sorted.size()));
        }

        /**
         * Fetch a file, memoising the result.
         *
         * <p>The same manifest is often reached through more than one
         * path (a root {@code package.json} and a nested one), and the
         * walk is the only expensive part of the analysis.
         */
        String fetch(String path) {
            if (contentCache.containsKey(path)) {
                return contentCache.get(path);
            }
            String content = null;
            try {
                content = githubClient.getFileContent(token, owner, repo, path, branch);
            } catch (Exception e) {
                log.warn("Could not read {}/{}/{}: {}", owner, repo, path, e.getMessage());
            }
            contentCache.put(path, content);
            return content;
        }
    }

    private static boolean isEntryPoint(String path, String fileName) {
        if (ENTRY_POINT_NAMES.contains(fileName)) {
            return true;
        }
        // A "main"/"app"/"server"/"index" file anywhere near the root.
        String base = fileName.toLowerCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        if (depthOf(path) > 2) {
            return false;
        }
        return base.equals("main") || base.equals("app") || base.equals("server")
                || base.equals("index") || base.equals("cli");
    }

    private static boolean isSourceFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".ts")
                || lower.endsWith(".tsx") || lower.endsWith(".js") || lower.endsWith(".jsx")
                || lower.endsWith(".kt") || lower.endsWith(".go") || lower.endsWith(".rb")
                || lower.endsWith(".cs") || lower.endsWith(".php");
    }

    private static int depthOf(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static Map<String, Integer> countByLanguage(List<String> paths) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String path : paths) {
            String language = languageOf(path);
            if (language.isEmpty()) {
                continue;
            }
            counts.merge(language, 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> countTopLevelDirectories(List<String> paths) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            counts.merge(path.substring(0, slash), 1, Integer::sum);
        }
        return counts;
    }

    private static String languageOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".sql")) return "sql";
        return "";
    }

    private static String truncate(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "\n… (truncated)";
    }
}
