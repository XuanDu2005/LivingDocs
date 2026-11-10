package com.livingdocs.modules.version.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The request of a bulk rollback — one rollback applied to a whole
 * collection of documents.
 *
 * <p>The single-document rollback ({@code POST /documents/{id}/rollback})
 * names one document and one version. That is the wrong shape for the
 * situation the bulk endpoint exists for: an automatic update went
 * wrong and landed on every document of a repository, a module or a
 * workspace, and the author has to undo all of them at once.
 *
 * <h2>Selecting the collection</h2>
 * <p>Three axes may be used, and they are combined with OR:
 * <ul>
 *     <li>{@code documentIds} — an explicit selection, e.g. the rows a
 *         user ticked in the UI;</li>
 *     <li>{@code repositoryId} — every document of one repository;</li>
 *     <li>{@code docType} — every document of one documentation type.</li>
 * </ul>
 * <p>When none of them is given, the collection is every document of the
 * workspace. The collection is always intersected with the workspace of
 * the URL, so a document of another workspace can never be rolled back
 * through this endpoint.
 *
 * <h2>Choosing the target</h2>
 * <p>{@link #strategy()} says how the target version of each document is
 * found:
 * <ul>
 *     <li>{@link Strategy#PREVIOUS_PUBLISHED} — every document is
 *         restored to the version that was published before its current
 *         head. This is the "undo the bad auto-update" case.</li>
 *     <li>{@link Strategy#VERSION_NUMBER} — every document is restored
 *         to the same version number, given in
 *         {@link #targetVersionNumber()}. This is the "a bad batch was
 *         published" case, where the documents share a version number
 *         because they were updated together.</li>
 * </ul>
 */
public record BulkRollbackRequest(
        /** The explicit selection. May be {@code null} or empty. */
        List<UUID> documentIds,
        /** Every document of this repository. May be {@code null}. */
        UUID repositoryId,
        /** Every document of this documentation type. May be {@code null}. */
        String docType,
        /** How the target version of each document is chosen. */
        Strategy strategy,
        /**
         * The version number every document is restored to. Only used by
         * {@link Strategy#VERSION_NUMBER}.
         */
        Integer targetVersionNumber,
        /**
         * Why the rollback was run.
         *
         * <p>Recorded in the change summary of every created version and
         * in the audit trail of the batch, so a reader of the history can
         * tell a mass correction from a series of unrelated edits.
         */
        @Size(max = 500) String reason) {

    /**
     * How the target version of a document is chosen.
     */
    public enum Strategy {
        /**
         * Restore the version that was published before the current head.
         */
        PREVIOUS_PUBLISHED,
        /**
         * Restore a specific version number of every document.
         */
        VERSION_NUMBER
    }

    /**
     * The strategy, defaulting to {@link Strategy#PREVIOUS_PUBLISHED}
     * when the caller does not name one.
     */
    public Strategy strategyOrDefault() {
        return strategy == null ? Strategy.PREVIOUS_PUBLISHED : strategy;
    }
}
