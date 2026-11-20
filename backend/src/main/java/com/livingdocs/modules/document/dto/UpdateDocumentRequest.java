package com.livingdocs.modules.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for updating an existing document's metadata (NOT its body — body
 * changes go through {@link com.livingdocs.modules.version.service.DocumentVersionService}).
 *
 * <p>The nullable fields share one rule: {@code null} means "leave this
 * field alone", because the endpoint is a full metadata update and most
 * callers only touch one or two fields. An empty string is the
 * explicit way to clear a field that may be empty — the summary.
 */
public record UpdateDocumentRequest(
        @NotBlank @Size(max = 255) String title,
        /**
         * The summary of the document.
         *
         * <p>{@code null} leaves the current summary alone; an empty
         * string clears it.
         */
        @Size(max = 1000) String summary,
        UUID repositoryId,
        UUID templateId,
        /**
         * The new owner of the document.
         *
         * <p>{@code null} means "leave the owner alone" — the field is
         * optional because most callers only touch the title or the
         * summary. Assigning an owner is a separate act from editing a
         * document, and it is guarded by a stricter rule (workspace
         * manager only), so it must not be reachable by accident.
         */
        UUID ownerId,
        boolean autoUpdateEnabled,
        /**
         * The path of the file this document is published to, inside the
         * repository.
         *
         * <p>{@code null} leaves the current path alone. This is the
         * field that ties the document to the repository: a proposal
         * raised for this file resolves back to this document, and the
         * commit dialog writes to this path instead of guessing one.
         */
        @Size(max = 512) String filePath
) {
    /**
     * The shape of the request before the file path was part of it.
     *
     * <p>Kept so the callers that do not care about the path do not have
     * to spell out a {@code null} for it.
     */
    public UpdateDocumentRequest(String title, String summary, UUID repositoryId,
                                  UUID templateId, UUID ownerId, boolean autoUpdateEnabled) {
        this(title, summary, repositoryId, templateId, ownerId, autoUpdateEnabled, null);
    }
}