package com.livingdocs.modules.template.controller;

import com.livingdocs.modules.template.dto.DocTemplateResponse;
import com.livingdocs.modules.template.dto.TemplateSchemaResponse;
import com.livingdocs.modules.template.model.DocType;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only metadata endpoints used by the visual builder. Nothing here
 * writes to the database; the actual template CRUD lives on
 * {@link DocTemplateController}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Templates", description = "Documentation template management")
public class TemplateMetadataController {

    private final TemplateSchema schema;
    private final DocTemplateService service;

    public TemplateMetadataController(TemplateSchema schema, DocTemplateService service) {
        this.schema = schema;
        this.service = service;
    }

    @GetMapping("/template-schema")
    @Operation(summary = "Return the canonical template body schema + sample + placeholder fields")
    public TemplateSchemaResponse schema() {
        List<TemplateSchemaResponse.PlaceholderFieldDoc> fields = schema.placeholderFieldDocs().stream()
                .map(m -> new TemplateSchemaResponse.PlaceholderFieldDoc(
                        m.get("field"), m.get("type"), m.get("description")))
                .toList();
        List<TemplateSchemaResponse.DocTypeInfo> types = java.util.Arrays.stream(DocType.values())
                .map(t -> new TemplateSchemaResponse.DocTypeInfo(
                        t.name(),
                        humanLabel(t),
                        t.getDescription()))
                .toList();
        return new TemplateSchemaResponse(1, schema.canonicalSample(), fields, types);
    }

    @GetMapping("/workspaces/{workspaceId}/templates/{templateId}/preview")
    @Operation(summary = "Render a template body to a Markdown preview")
    public Map<String, Object> preview(@PathVariable UUID workspaceId,
                                        @PathVariable UUID templateId) {
        DocTemplateResponse t = service.get(com.livingdocs.common.security.CurrentUser.requireId(),
                workspaceId, templateId);
        return Map.of("templateId", t.id(), "markdown", renderPreview(t.bodyJson(), t.name()));
    }

    private String humanLabel(DocType t) {
        StringBuilder b = new StringBuilder();
        for (String w : t.name().toLowerCase().split("_")) {
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /**
     * Render a template body to a quick Markdown preview. This is a
     * best-effort renderer for the visual builder — the AI service
     * later replaces each placeholder with real content.
     */
    private String renderPreview(String bodyJson, String templateName) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(bodyJson);
            StringBuilder out = new StringBuilder();
            String titleHint = root.has("titleHint") ? root.get("titleHint").asText() : templateName;
            out.append("# ").append(titleHint).append("\n\n");
            if (root.has("sections") && root.get("sections").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode sec : root.get("sections")) {
                    int level = sec.has("level") ? sec.get("level").asInt(2) : 2;
                    String heading = sec.has("heading") ? sec.get("heading").asText("") : "";
                    out.append("#".repeat(Math.max(1, Math.min(6, level))))
                       .append(' ').append(heading).append("\n\n");
                    if (sec.has("placeholders") && sec.get("placeholders").isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode ph : sec.get("placeholders")) {
                            String key = ph.has("key") ? ph.get("key").asText("") : "";
                            String prompt = ph.has("prompt") ? ph.get("prompt").asText("") : "";
                            out.append("- **[`").append(key).append("`]** — ").append(prompt).append("\n");
                        }
                        out.append("\n");
                    }
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "_Preview unavailable: " + e.getMessage() + "_";
        }
    }
}