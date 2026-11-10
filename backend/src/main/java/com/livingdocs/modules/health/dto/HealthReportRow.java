package com.livingdocs.modules.health.dto;

import java.util.List;
import java.util.UUID;

/**
 * One row of the "Health &amp; Compliance" report.
 *
 * <p>A row is a document, seen through the three questions the report
 * answers about it:
 *
 * <ul>
 *   <li><b>Is it fresh?</b> — {@code lastUpdated}, {@code daysSinceUpdate}
 *       and {@code lastVersion}.</li>
 *   <li><b>Does it follow its template?</b> — {@code templateName},
 *       {@code templateCompliant} and {@code missingHeadings}.</li>
 *   <li><b>Has the code moved away from it?</b> — {@code openDrift} and
 *       {@code maxDriftSeverity}.</li>
 * </ul>
 *
 * <p>The row is a plain value object: it carries no behaviour and no
 * reference to a JPA entity, so the exporter can render it without
 * touching the persistence context.
 *
 * @param documentId       the document the row is about
 * @param title            the display title of the document
 * @param slug             the URL slug of the document
 * @param docType          the documentation type, e.g. {@code API_REFERENCE}
 * @param status           the workflow status of the document
 * @param ownerId          the user who owns the document
 * @param templateName     the name of the template the document follows,
 *                         or {@code null} when it has none
 * @param templateCompliant
 *                         whether the published body follows the template
 *                         completely; {@code null} when there is no
 *                         template to comply with
 * @param missingHeadings  the template headings the document does not have
 * @param lastVersion      the number of the newest version of the document
 * @param lastUpdated      when the document was last touched
 * @param openDrift        the number of unresolved drift alerts
 * @param maxDriftSeverity the worst severity among those alerts, or
 *                         {@code null} when there is none
 * @param daysSinceUpdate  how many days ago the document was last touched
 */
public record HealthReportRow(
        UUID documentId,
        String title,
        String slug,
        String docType,
        String status,
        UUID ownerId,
        String templateName,
        Boolean templateCompliant,
        List<String> missingHeadings,
        Integer lastVersion,
        String lastUpdated,
        Integer openDrift,
        String maxDriftSeverity,
        Long daysSinceUpdate
) {
    /**
     * A row for a document that has no template at all.
     *
     * <p>{@code templateCompliant} stays {@code null} rather than
     * {@code true}: "there is no template" is not the same statement as
     * "the document follows the template", and a report that conflates
     * the two misleads its reader.
     */
    public static HealthReportRow withoutTemplate(UUID documentId,
                                                   String title,
                                                   String slug,
                                                   String docType,
                                                   String status,
                                                   UUID ownerId,
                                                   Integer lastVersion,
                                                   String lastUpdated,
                                                   Integer openDrift,
                                                   String maxDriftSeverity,
                                                   Long daysSinceUpdate) {
        return new HealthReportRow(
                documentId, title, slug, docType, status, ownerId,
                null, null, List.of(),
                lastVersion, lastUpdated, openDrift, maxDriftSeverity, daysSinceUpdate);
    }
}
