package com.livingdocs.modules.template.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.text.MarkdownSections;
import com.livingdocs.modules.template.model.DocTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Compares a document body against the sections of a template.
 *
 * <p>This is a <b>pure function</b>: it takes a template and a markdown
 * body and returns what is missing, what is out of order and what is
 * empty. It touches no repository, so any module may use it — the
 * review module (to show a reviewer a badge) and the version module
 * (to decide whether a version may be published) alike. Keeping it
 * free of dependencies is what avoids a circular dependency between
 * those two modules.
 *
 * <p>The template body is the canonical JSON described by
 * {@link TemplateSchema}:
 * <pre>{@code
 * {
 *   "strict": false,
 *   "sections": [
 *     { "id": "overview", "heading": "Overview", "required": true }
 *   ]
 * }
 * }</pre>
 */
@Component
public class TemplateComplianceEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TemplateComplianceEvaluator.class);

    private final TemplateSchema templateSchema;
    private final ObjectMapper objectMapper;

    public TemplateComplianceEvaluator(TemplateSchema templateSchema, ObjectMapper objectMapper) {
        this.templateSchema = templateSchema;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate one body against one template.
     *
     * @param template     the template the body should follow; may be
     *                     {@code null}, in which case there is nothing
     *                     to comply with
     * @param bodyMarkdown the markdown body of the version
     */
    public Result evaluate(DocTemplate template, String bodyMarkdown) {
        if (template == null) {
            return Result.none();
        }
        List<SectionSpec> specs = parseSections(template);
        boolean strict = readStrict(template);
        List<MarkdownSections.Section> headings = MarkdownSections.parse(bodyMarkdown);
        String[] lines = (bodyMarkdown == null ? "" : bodyMarkdown).split("\\R", -1);

        List<Result.SectionResult> sections = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> missingRequired = new ArrayList<>();
        List<String> outOfOrder = new ArrayList<>();

        int cursor = -1;
        for (SectionSpec spec : specs) {
            MarkdownSections.Section match = matchHeading(headings, spec);
            boolean present = match != null;
            boolean empty = present && isEmptyUnder(lines, match);
            boolean inOrder = present && match.line() > cursor;

            if (present) {
                cursor = match.line();
            }
            if (!present) {
                missing.add(spec.heading());
                if (spec.required()) {
                    missingRequired.add(spec.heading());
                }
            } else if (!inOrder) {
                outOfOrder.add(spec.heading());
            }
            sections.add(new Result.SectionResult(
                    spec.id(), spec.heading(), present, inOrder, empty, spec.required()));
        }

        boolean compliant = missing.isEmpty() && outOfOrder.isEmpty();
        return new Result(
                template.getId(),
                template.getName(),
                strict,
                compliant,
                sections,
                missing,
                missingRequired,
                outOfOrder);
    }

    /**
     * Whether the template asks for its sections to be enforced.
     *
     * <p>{@code strict} is a workspace-level choice stored in the
     * template body. It defaults to {@code false}: a template that
     * does not say anything about it does not block a publication.
     */
    private boolean readStrict(DocTemplate template) {
        JsonNode root = readBody(template);
        if (root == null) {
            return false;
        }
        JsonNode strict = root.get("strict");
        return strict != null && strict.isBoolean() && strict.asBoolean();
    }

    /**
     * The ordered sections of a template.
     *
     * <p>The template body is normalised first, so a legacy
     * {@code placeholders: [...]} template still yields one section
     * instead of none.
     */
    private List<SectionSpec> parseSections(DocTemplate template) {
        List<SectionSpec> specs = new ArrayList<>();
        JsonNode root = readBody(template);
        if (root == null) {
            return specs;
        }
        JsonNode sections = root.get("sections");
        if (sections == null || !sections.isArray()) {
            return specs;
        }
        for (JsonNode section : sections) {
            if (section == null || !section.isObject()) {
                continue;
            }
            JsonNode id = section.get("id");
            JsonNode heading = section.get("heading");
            if (heading == null || !heading.isTextual() || heading.asText().isBlank()) {
                continue;
            }
            // A section is required unless the template says otherwise.
            boolean required = true;
            JsonNode req = section.get("required");
            if (req != null && req.isBoolean()) {
                required = req.asBoolean();
            }
            specs.add(new SectionSpec(
                    id != null && id.isTextual()
                            ? id.asText()
                            : MarkdownSections.slugify(heading.asText()),
                    heading.asText(),
                    required));
        }
        return specs;
    }

    /**
     * The parsed body of a template, or {@code null} when it cannot be
     * read.
     *
     * <p>A template with an unreadable body must not fail every
     * document in the workspace — the check skips it instead.
     */
    private JsonNode readBody(DocTemplate template) {
        String body;
        try {
            body = templateSchema.normalise(template.getBody());
        } catch (Exception e) {
            log.warn("Template {} has an unreadable body — skipping the compliance check: {}",
                    template.getId(), e.getMessage());
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (Exception e) {
            log.warn("Template {} body is not valid JSON — skipping the compliance check: {}",
                    template.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Find the heading in the document that matches a template section.
     *
     * <p>Matching is by slug first — that is what the template section
     * id usually is — and falls back to a case-insensitive comparison
     * of the heading text, so a template that says "API surface" still
     * matches a document heading "API Surface".
     */
    private MarkdownSections.Section matchHeading(List<MarkdownSections.Section> headings,
                                                  SectionSpec spec) {
        String slug = MarkdownSections.slugify(spec.heading());
        for (MarkdownSections.Section h : headings) {
            if (h.slug().equalsIgnoreCase(slug)) {
                return h;
            }
        }
        for (MarkdownSections.Section h : headings) {
            if (h.heading().equalsIgnoreCase(spec.heading())) {
                return h;
            }
        }
        return null;
    }

    /**
     * Whether a heading has any content under it.
     *
     * <p>A heading with nothing but blank lines and the next heading
     * under it is an empty section — the reviewer should know, because
     * "the section is there" and "the section says something" are very
     * different things.
     */
    private boolean isEmptyUnder(String[] lines, MarkdownSections.Section target) {
        int start = target.line() + 1;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                // The next heading ends this section.
                return true;
            }
            return false;
        }
        return true;
    }

    /**
     * One section of a template, as the compliance check sees it.
     */
    private record SectionSpec(String id, String heading, boolean required) {
    }

    /**
     * The outcome of the comparison.
     *
     * @param templateId           the template that was checked
     * @param templateName         the display name of that template
     * @param strict               whether the template asks for its
     *                             structure to be enforced on publish
     * @param compliant            whether nothing is missing and nothing
     *                             is out of order
     * @param sections             the state of every template section
     * @param missingHeadings      every heading the document does not have
     * @param missingRequiredHeadings
     *                             the subset of {@code missingHeadings}
     *                             that the template marks as required
     * @param outOfOrderHeadings   the headings that are present but in
     *                             the wrong order
     */
    public record Result(
            UUID templateId,
            String templateName,
            boolean strict,
            boolean compliant,
            List<SectionResult> sections,
            List<String> missingHeadings,
            List<String> missingRequiredHeadings,
            List<String> outOfOrderHeadings
    ) {
        /**
         * The state of one template section inside the document body.
         *
         * @param id       the template section id, e.g. {@code overview}
         * @param heading  the heading the template asks for
         * @param present  whether the document has a matching heading
         * @param inOrder  whether the heading appears in the template's order
         * @param empty    whether the heading exists but has no content under it
         * @param required whether the template marks the section as required
         */
        public record SectionResult(
                String id,
                String heading,
                boolean present,
                boolean inOrder,
                boolean empty,
                boolean required
        ) {
        }

        /**
         * The result of a document that has no template to comply with.
         */
        public static Result none() {
            return new Result(null, null, false, true,
                    List.of(), List.of(), List.of(), List.of());
        }
    }
}
