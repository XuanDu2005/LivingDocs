package com.livingdocs.modules.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.text.MarkdownSections;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.review.dto.TemplateComplianceResponse;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateSchema;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Checks a document version against the template it was generated from.
 *
 * <p>Requirement #5 of the STAFF role: "verify template structure".
 *
 * <p>The template library already had a validator, but it only checked
 * the <em>shape of the template definition</em> — that every section
 * has an id and a heading — at the moment an administrator saved it.
 * Nothing ever compared the generated document against that template,
 * so a reviewer had to open the document and check by hand whether the
 * "API surface" section was there at all.
 *
 * <p>This service closes that gap. It resolves the template the document
 * uses, parses the headings out of the version body, and reports which
 * sections are present, which are missing, which are out of order and
 * which are empty.
 */
@Service
public class TemplateComplianceService {

    private static final Logger log = LoggerFactory.getLogger(TemplateComplianceService.class);

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocTemplateRepository templateRepository;
    private final DocTemplateService templateService;
    private final TemplateSchema templateSchema;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public TemplateComplianceService(DocumentRepository documentRepository,
                                     DocumentVersionRepository versionRepository,
                                     DocTemplateRepository templateRepository,
                                     DocTemplateService templateService,
                                     TemplateSchema templateSchema,
                                     WorkspaceService workspaceService,
                                     ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.templateRepository = templateRepository;
        this.templateService = templateService;
        this.templateSchema = templateSchema;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    /**
     * Check one version of one document against its template.
     *
     * <p>Requires workspace membership — every role in the workspace may
     * look at a document's structure; the reviewer uses this to decide
     * whether the content is complete.
     */
    @Transactional(readOnly = true)
    public TemplateComplianceResponse check(UUID actorId, UUID documentId, UUID versionId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());

        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!version.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }

        DocTemplate template = resolveTemplate(document, version);
        if (template == null) {
            // Nothing to comply with. Reporting "not compliant" would be
            // misleading — there is simply no template for this document.
            return new TemplateComplianceResponse(
                    null, null, true, List.of(), List.of(), List.of());
        }

        return evaluate(template, version.getBodyMarkdown());
    }

    /**
     * Compare a body against a template's sections.
     *
     * <p>Split out from {@link #check} so the review queue can evaluate
     * the same template against many documents without repeating the
     * template parsing.
     */
    public TemplateComplianceResponse evaluate(DocTemplate template, String bodyMarkdown) {
        List<TemplateSpec> specs = parseSections(template);
        List<MarkdownSections.Section> headings = MarkdownSections.parse(bodyMarkdown);
        String[] lines = (bodyMarkdown == null ? "" : bodyMarkdown).split("\\R", -1);

        List<TemplateComplianceResponse.SectionCompliance> sections = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> outOfOrder = new ArrayList<>();

        int cursor = -1;
        for (TemplateSpec spec : specs) {
            MarkdownSections.Section match = matchHeading(headings, spec);
            boolean present = match != null;
            boolean empty = present && isEmptyUnder(lines, match);
            boolean inOrder = present && match.line() > cursor;

            if (present) {
                cursor = match.line();
            }
            if (!present) {
                missing.add(spec.heading());
            } else if (!inOrder) {
                outOfOrder.add(spec.heading());
            }
            sections.add(new TemplateComplianceResponse.SectionCompliance(
                    spec.id(), spec.heading(), present, inOrder, empty, spec.required()));
        }

        boolean compliant = missing.isEmpty() && outOfOrder.isEmpty();
        return new TemplateComplianceResponse(
                template.getId(),
                template.getName(),
                compliant,
                sections,
                missing,
                outOfOrder);
    }

    /**
     * The template a document version was generated against.
     *
     * <p>Prefers the version's own template id (that is the template
     * that actually shaped this version), then the document's template,
     * and finally the workspace default for the document type.
     */
    private DocTemplate resolveTemplate(Document document, DocumentVersion version) {
        if (version.getTemplateId() != null) {
            DocTemplate t = templateRepository.findById(version.getTemplateId()).orElse(null);
            if (t != null) {
                return t;
            }
        }
        if (document.getTemplateId() != null) {
            DocTemplate t = templateRepository.findById(document.getTemplateId()).orElse(null);
            if (t != null) {
                return t;
            }
        }
        return templateService.resolveDefault(document.getWorkspaceId(), document.getDocType());
    }

    /**
     * The ordered sections of a template.
     *
     * <p>The template body is normalised first, so a legacy
     * {@code placeholders: [...]} template still yields one section
     * instead of none.
     */
    private List<TemplateSpec> parseSections(DocTemplate template) {
        List<TemplateSpec> specs = new ArrayList<>();
        String body;
        try {
            body = templateSchema.normalise(template.getBody());
        } catch (Exception e) {
            log.warn("Template {} has an unreadable body — skipping the compliance check: {}",
                    template.getId(), e.getMessage());
            return specs;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Template {} body is not valid JSON — skipping the compliance check: {}",
                    template.getId(), e.getMessage());
            return specs;
        }
        JsonNode sections = root == null ? null : root.get("sections");
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
            boolean required = true;
            JsonNode req = section.get("required");
            if (req != null && req.isBoolean()) {
                required = req.asBoolean();
            }
            specs.add(new TemplateSpec(
                    id != null && id.isTextual()
                            ? id.asText()
                            : MarkdownSections.slugify(heading.asText()),
                    heading.asText(),
                    required));
        }
        return specs;
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
                                                  TemplateSpec spec) {
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
    private record TemplateSpec(String id, String heading, boolean required) {
    }
}
