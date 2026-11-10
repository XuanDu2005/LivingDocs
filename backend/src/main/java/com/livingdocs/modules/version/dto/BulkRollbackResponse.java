package com.livingdocs.modules.version.dto;

import java.util.List;

/**
 * The response of a bulk rollback.
 *
 * <p>It carries one outcome per document of the collection — not only
 * the successful ones — plus the counters the UI needs to render a
 * summary ("12 of 14 documents rolled back, 2 skipped").
 */
public record BulkRollbackResponse(
        /** The number of documents the batch touched. */
        int total,
        /** The number of documents that were rolled back. */
        int rolledBack,
        /** The number of documents that could not be rolled back. */
        int skipped,
        /** One entry per document of the collection. */
        List<DocumentRollbackResult> results) {
}
