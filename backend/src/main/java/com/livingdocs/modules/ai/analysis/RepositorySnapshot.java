package com.livingdocs.modules.ai.analysis;

import java.util.List;
import java.util.Map;

/**
 * A point-in-time picture of a repository, taken from GitHub.
 *
 * <p>This is the grounding for a repository-level documentation run.
 * The AI service has AST analysers for code files, but a README or an
 * architecture guide cannot be written from parsed classes alone — it
 * needs to know how the repository is <em>laid out</em>, what it
 * depends on, and how it is started. The snapshot carries exactly that.
 *
 * @param fullName        the {@code owner/name} of the repository
 * @param description     the repository description from GitHub, may be {@code null}
 * @param defaultBranch   the branch the snapshot was taken from
 * @param tree            every file path found, in scan order
 * @param languages       file count per language, e.g. {@code {"java": 42}}
 * @param directories     the top-level directories, with their file counts
 * @param manifests       the content of the dependency manifests, keyed by path
 * @param entryPoints     the files that look like the entry point of the project
 * @param keyFiles        a sample of the most interesting source files
 * @param totalFiles      how many files the repository contains
 * @param truncated       whether the tree was cut short by the size limit
 */
public record RepositorySnapshot(
        String fullName,
        String description,
        String defaultBranch,
        List<String> tree,
        Map<String, Integer> languages,
        Map<String, Integer> directories,
        Map<String, String> manifests,
        List<String> entryPoints,
        List<KeyFile> keyFiles,
        int totalFiles,
        boolean truncated
) {

    /**
     * A source file included in the snapshot, with its content.
     *
     * @param path    the repository-relative path
     * @param content the file content, possibly truncated
     * @param language the detected language, or an empty string
     */
    public record KeyFile(String path, String content, String language) {}
}
