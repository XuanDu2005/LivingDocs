package com.livingdocs.modules.github.dto;

/**
 * Response returned after successfully opening a pull request that
 * publishes a document to a repository.
 */
public record CommitDocumentResponse(
        String pullRequestUrl,
        String headBranch,
        String filePath,
        String commitMessage
) {}
