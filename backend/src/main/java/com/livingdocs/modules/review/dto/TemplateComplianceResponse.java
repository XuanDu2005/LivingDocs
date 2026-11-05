package com.livingdocs.modules.review.dto;

import java.util.List;
import java.util.UUID;

/**
 * Whether a document version actually follows its template.
 *
 * <p>Requirement #5 of the STAFF role: "verify template structure".
 * Before this check existed the template was only ever validated as a
 * JSON document at authoring time — nothing compared the <em>generated
 * documentation</em> against the sections the template asks for, so a
 * reviewer had to eyeball it.
 *
 * <p>The check is purely structural: every section of the template must
 * appear as a markdown heading in the body, the headings must appear in
 * the template's order, and a section must not be empty.
 */
public record TemplateComplianceResponse(
        UUID templateId,
        String templateName,
        boolean compliant,
        List<SectionCompliance> sections,
        List<String> missingHeadings,
        List<String> outOfOrderHeadings
) {
    /**
     * The state of one template section inside the document body.
     *
     * @param id       the template section id, e.g. {@code overview}
     * @param heading  the heading the template asks for, e.g. {@code Overview}
     * @param present  whether the document has a matching heading
     * @param inOrder  whether the heading appears in the template's order
     * @param empty    whether the heading exists but has no content under it
     * @param required whether the template marks the section as required
     */
    public record SectionCompliance(
            String id,
            String heading,
            boolean present,
            boolean inOrder,
            boolean empty,
            boolean required
    ) {
    }
}
