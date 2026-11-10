package com.livingdocs.modules.template.service;

import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.model.DocType;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Resolves the template schema that applies to a document.
 *
 * <p>This is the <b>single source of truth</b> for "which template
 * shapes this document?". Every AI entry point goes through here, so a
 * workspace's custom template always reaches the AI service — regardless
 * of whether the run was started by a user, by the auto-update webhook,
 * or by the bulk generation worker.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>The template pinned to the document version (a version records
 *       the template it was generated from).</li>
 *   <li>The template pinned to the document.</li>
 *   <li>The workspace's default template for the document's doc type.</li>
 *   <li>The global default template for the doc type.</li>
 * </ol>
 *
 * <p>Steps 3 and 4 are implemented by
 * {@link DocTemplateService#resolveDefault(UUID, String)}, which already
 * prefers the workspace default over the global one.
 */
@Service
public class TemplateSchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(TemplateSchemaResolver.class);

    private final DocTemplateService templateService;
    private final DocTemplateRepository templateRepository;

    public TemplateSchemaResolver(DocTemplateService templateService,
                                   DocTemplateRepository templateRepository) {
        this.templateService = templateService;
        this.templateRepository = templateRepository;
    }

    /**
     * The template entity that applies to a document version.
     *
     * <p>Same resolution order as {@link #resolveForDocument}: the
     * version's own template, then the document's template, then the
     * workspace default for the document's doc type.
     *
     * <p>Used by the publish guard, which needs to know <em>which</em>
     * template a version has to follow — not just its body.
     *
     * @return the template, or {@code null} when no template applies
     */
    @Transactional(readOnly = true)
    public DocTemplate resolveTemplate(Document document, DocumentVersion version) {
        if (document == null) {
            return null;
        }
        if (version != null && version.getTemplateId() != null) {
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
     * The template entity that applies to a document, ignoring any
     * version.
     *
     * <p>This is the lookup the runtime trigger gate needs: before a
     * regeneration is queued we have to know which template's
     * {@code auto_generate_on_*} flags apply. There is no new version
     * yet at that point, so the version-pinned branch of
     * {@link #resolveTemplate(Document, DocumentVersion)} cannot be
     * used — the resolution falls back to the template pinned to the
     * document, then to the workspace default for its doc type.
     *
     * @return the template, or {@code null} when no template applies
     */
    @Transactional(readOnly = true)
    public DocTemplate resolveTemplateForDocument(Document document) {
        return resolveTemplate(document, null);
    }

    /**
     * The JSON schema of the template that applies to a document.
     *
     * @return the canonical template body JSON, or {@code null} when no
     *         template applies — the AI service then falls back to its
     *         built-in preset for the doc type.
     */
    @Transactional(readOnly = true)
    public String resolveForDocument(Document document, DocumentVersion version) {
        if (document == null) {
            return null;
        }
        return bodyWithName(resolveTemplate(document, version));
    }

    /**
     * The JSON schema for a doc type — used when there is no document yet
     * (e.g. the bulk generation worker, which documents entities that
     * have no document).
     *
     * @return the canonical template body JSON, or {@code null} when no
     *         template applies.
     */
    @Transactional(readOnly = true)
    public String resolveForDocType(UUID workspaceId, String docType) {
        if (docType == null || docType.isBlank()) {
            return null;
        }
        DocTemplate t = templateService.resolveDefault(workspaceId, docType);
        return bodyWithName(t);
    }

    /**
     * Return the template body with a {@code name} field added.
     *
     * <p>The AI service renders the template's name into the system
     * prompt ("Follow the 'X' documentation template exactly"). The body
     * JSON as stored does not carry the template's display name, so it is
     * injected here. If the body already has a {@code name}, it wins —
     * the stored value is authoritative.
     *
     * <p>Returns {@code null} when there is no template. A body that
     * cannot be parsed is returned unchanged: the AI service treats an
     * unparseable schema as "no schema" and falls back to its preset,
     * which is better than failing the whole generation.
     */
    private String bodyWithName(DocTemplate template) {
        if (template == null) {
            return null;
        }
        String body = template.getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body);
            if (root == null || !root.isObject()) {
                return body;
            }
            if (!root.hasNonNull("name") && template.getName() != null) {
                root.put("name", template.getName());
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.debug("Could not enrich template {} body with its name: {}",
                    template.getSlug(), e.getMessage());
            return body;
        }
    }

    /**
     * The JSON schema of a <em>specific</em> template, identified by its
     * slug.
     *
     * <p>Used when the user picks a template explicitly in the UI (the
     * template picker sends the slug). The selected template wins over
     * the document's default — otherwise picking a template would be a
     * no-op.
     *
     * @return the canonical template body JSON, or {@code null} when no
     *         template with that slug exists.
     */
    @Transactional(readOnly = true)
    public String resolveSchemaForTemplate(UUID workspaceId, String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        DocTemplate t = templateRepository.findAllBySlugOrderByVersionDesc(slug.trim()).stream()
                .findFirst()
                .orElse(null);
        if (t == null) {
            return null;
        }
        // A global template is visible to every workspace; a workspace
        // template must belong to the workspace that asked for it.
        if (t.getWorkspaceId() != null && !t.getWorkspaceId().equals(workspaceId)) {
            log.debug("Template {} belongs to another workspace — ignoring it", slug);
            return null;
        }
        return bodyWithName(t);
    }

    /**
     * Resolve a template identifier into everything the AI needs.
     *
     * <p>Combines {@link #resolveDocType(UUID, String)} with the schema
     * lookup so a caller never has to do both. When the identifier names
     * an actual template (a slug), that template's own body is used —
     * this is what makes picking a template in the UI actually change
     * the output. When it is only a doc type, the workspace's default
     * template for that doc type is used.
     *
     * @param templateOrSlug a canonical doc type or a template slug
     */
    @Transactional(readOnly = true)
    public ResolvedTemplate resolveTemplate(UUID workspaceId, String templateOrSlug) {
        String docType = resolveDocType(workspaceId, templateOrSlug);
        String schema = resolveSchemaForTemplate(workspaceId, templateOrSlug);
        if (schema == null) {
            schema = resolveForDocType(workspaceId, docType);
        }
        return new ResolvedTemplate(docType, schema);
    }

    /**
     * A template identifier resolved into its canonical doc type and its
     * schema JSON.
     *
     * @param docType the canonical doc type, never {@code null}
     * @param schema the canonical template body JSON, or {@code null}
     *               when no template applies
     */
    public record ResolvedTemplate(String docType, String schema) {}

    /**
     * Map a template identifier onto the canonical doc type.
     *
     * <p>Callers hand us two different kinds of identifier:
     * <ul>
     *   <li>a canonical doc type ({@code API_REFERENCE}) — from the
     *       document itself or from the bulk job, and</li>
     *   <li>a template slug ({@code default-api-reference}) — from the
     *       frontend's template picker.</li>
     * </ul>
     *
     * <p>Both have to end up as the same canonical doc type, because that
     * is the key the template resolver and the AI service's preset lookup
     * use. Without this normalisation a slug like {@code default-adr}
     * silently fell through to the {@code MODULE_GUIDE} preset.
     *
     * @return the canonical doc type name, or {@code MODULE_GUIDE} when
     *         nothing could be resolved.
     */
    @Transactional(readOnly = true)
    public String resolveDocType(UUID workspaceId, String templateOrSlug) {
        if (templateOrSlug == null || templateOrSlug.isBlank()) {
            return "MODULE_GUIDE";
        }
        String raw = templateOrSlug.trim();

        // 1. Already a canonical doc type?
        DocType parsed = DocType.parse(raw);
        if (parsed != DocType.CUSTOM) {
            return parsed.name();
        }

        // 2. Treat it as a template slug and look up its doc type. The
        //    newest version of the slug wins.
        String bySlug = templateRepository.findAllBySlugOrderByVersionDesc(raw).stream()
                .findFirst()
                .map(DocTemplate::getDocType)
                .orElse(null);
        if (bySlug != null && !bySlug.isBlank()) {
            return bySlug;
        }

        // 3. A slug that follows the ``default-<docType>`` convention.
        String normalised = raw.toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalised.startsWith("DEFAULT_")) {
            normalised = normalised.substring("DEFAULT_".length());
        }
        DocType fromConvention = DocType.parse(normalised);
        if (fromConvention != DocType.CUSTOM) {
            return fromConvention.name();
        }
        return "MODULE_GUIDE";
    }
}
