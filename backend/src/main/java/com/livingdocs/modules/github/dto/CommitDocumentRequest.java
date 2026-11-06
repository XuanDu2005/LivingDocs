package com.livingdocs.modules.github.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for committing a document to a repository's default branch
 * as a pull request.
 *
 * <p>Path defaults to {@code docs/{slug}.md} on the frontend side; the
 * backend treats {@code filePath} as authoritative so callers can override
 * the convention if needed.
 *
 * <p>{@code documentId} names the LivingDocs document the commit
 * publishes, and {@code versionNumber} the exact version of it. Both are
 * mandatory because the commit is gated on the document having gone
 * through the review workflow: the gate has to know <em>which</em>
 * version is being published, otherwise a caller could point the request
 * at an approved version while sending the body of an unapproved one.
 */
public record CommitDocumentRequest(
        @NotBlank @Size(max = 500) String filePath,
        @NotBlank @Size(max = 1_000_000) String content,
        @NotBlank @Size(max = 500) String commitMessage,
        @NotBlank @Size(max = 500) String prTitle,
        @Size(max = 65_535) String prBody,
        @Size(max = 244) String headBranch,
        @NotNull java.util.UUID documentId,
        @NotNull Integer versionNumber
) {
}
