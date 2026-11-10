package com.livingdocs.modules.health.dto;

import java.util.List;
import java.util.UUID;

/**
 * One row of the compliance export.
 *
 * <p>Unlike the health report — which is one row per <em>document</em> —
 * the compliance export is one row per <em>live version</em>: every
 * version that is currently published (or approved and about to be)
 * is checked against the template it was generated from.
 *
 * <p>A document may contribute several rows: an approved version and a
 * published version are two different pieces of documentation, and the
 * reader of the report has to be able to see whether the one that is
 * live today still follows its template.
 *
 * @param documentId      the document the version belongs to
 * @param versionNumber   the number of the version
 * @param templateName    the name of the template the version was
 *                        generated from, or {@code null} when it has none
 * @param compliant       whether the body follows the template; {@code null}
 *                        when there is no template to comply with
 * @param missingHeadings the template headings the body does not have
 * @param outOfOrder      the headings that are present but in the wrong order
 * @param generatedBy     the role that produced the version
 * @param createdAt       when the version was created
 */
public record ComplianceReportRow(
        UUID documentId,
        Integer versionNumber,
        String templateName,
        Boolean compliant,
        List<String> missingHeadings,
        List<String> outOfOrder,
        String generatedBy,
        String createdAt
) {
    /**
     * A row for a version that has no template to comply with.
     */
    public static ComplianceReportRow withoutTemplate(UUID documentId,
                                                       Integer versionNumber,
                                                       String generatedBy,
                                                       String createdAt) {
        return new ComplianceReportRow(
                documentId, versionNumber, null, null,
                List.of(), List.of(), generatedBy, createdAt);
    }
}
