package com.livingdocs.modules.version.dto;

import com.livingdocs.modules.version.model.DocumentVersion;
import java.util.List;
import java.util.UUID;

/**
 * Wire-format for a diff between two versions of a document.
 *
 * <p>The diff itself is computed by the version service (line-based unified
 * diff is sufficient for the MVP — semantic diffs can be layered on top
 * later).
 */
public record DocumentVersionDiffResponse(
        UUID fromVersionId,
        Integer fromVersionNumber,
        UUID toVersionId,
        Integer toVersionNumber,
        String unifiedDiff,
        List<DocumentVersionResponse> from,
        List<DocumentVersionResponse> to
) {
    public static DocumentVersionDiffResponse of(DocumentVersion from, DocumentVersion to, String diff) {
        return new DocumentVersionDiffResponse(
                from.getId(),
                from.getVersionNumber(),
                to.getId(),
                to.getVersionNumber(),
                diff,
                List.of(DocumentVersionResponse.from(from)),
                List.of(DocumentVersionResponse.from(to))
        );
    }
}