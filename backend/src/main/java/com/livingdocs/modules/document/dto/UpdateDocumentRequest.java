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
        boolean autoUpdateEnabled
) {}