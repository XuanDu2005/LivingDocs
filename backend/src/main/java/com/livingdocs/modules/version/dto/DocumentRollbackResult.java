package com.livingdocs.modules.version.dto;

import com.livingdocs.modules.version.model.VersionStatus;

import java.util.UUID;

/**
 * The outcome of the rollback of one document inside a bulk rollback.
 *
 * <p>A bulk rollback is <em>partially</em> successful by nature: the
 * collection may contain a document that never had a published version,
 * or one whose target version was never approved. Such a document cannot
 * be rolled back, and refusing the whole batch because of it would be
 * worse than rolling back the documents that can be rolled back. Each
 * document therefore carries its own outcome, and the reason it failed.
 */
public record DocumentRollbackResult(
        UUID documentId,
        String documentTitle,
        /** Whether a rollback version was created for this document. */
        boolean rolledBack,
        /** The new version created by the rollback. {@code null} on failure. */
        UUID newVersionId,
        /** The number of the new version. {@code null} on failure. */
        Integer newVersionNumber,
        /** The version the content was restored from. {@code null} on failure. */
        Integer restoredFromVersion,
        /** The status of the created version — always {@code DRAFT}. */
        VersionStatus status,
        /** Why the document was skipped. {@code null} on success. */
        String reason) {

    public static DocumentRollbackResult success(UUID documentId,
                                                 String documentTitle,
                                                 UUID newVersionId,
                                                 Integer newVersionNumber,
                                                 Integer restoredFromVersion) {
        return new DocumentRollbackResult(documentId, documentTitle, true,
                newVersionId, newVersionNumber, restoredFromVersion,
                VersionStatus.DRAFT, null);
    }

    public static DocumentRollbackResult failure(UUID documentId,
                                                 String documentTitle,
                                                 String reason) {
        return new DocumentRollbackResult(documentId, documentTitle, false,
                null, null, null, null, reason);
    }
}
