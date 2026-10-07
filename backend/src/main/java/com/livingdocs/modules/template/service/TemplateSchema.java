package com.livingdocs.modules.template.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.livingdocs.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Canonical shape of a template body JSON. Visual builders in the UI
 * construct this structure; the AI service is also expected to read it
 * (sections + placeholders) when generating documentation.
 *
 * <p>Canonical shape:
 * <pre>{@code
 * {
 *   "version": 1,
 *   "titleHint": "Module Guide — {{moduleName}}",
 *   "sections": [
 *     { "id": "overview", "heading": "Overview",
 *       "level": 2,
 *       "placeholders": [
 *         { "key": "summary", "prompt": "One paragraph summary.",
 *           "required": true, "maxWords": 80 }
 *       ]
 *     },
 *     { "id": "api-surface", "heading": "API surface",
 *       "level": 2,
 *       "placeholders": [
 *         { "key": "endpoints", "prompt": "List public endpoints.",
 *           "binding": "code.route" }
 *       ]
 *     }
 *   ],
 *   "variables": [
 *     { "key": "moduleName", "default": "core", "description": "..." }
 *   ]
 * }
 * }</pre>
 *
 * <p>The validator only enforces shape; richer semantic checks happen
 * later when the AI service consumes the template.
 */
@Component
public class TemplateSchema {

    private final ObjectMapper objectMapper;

    public TemplateSchema(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode canonicalSample() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 1);
        root.put("titleHint", "Module Guide — {{moduleName}}");
        ArrayNode sections = root.putArray("sections");

        ObjectNode overview = sections.addObject();
        overview.put("id", "overview");
        overview.put("heading", "Overview");
        overview.put("level", 2);
        ArrayNode overviewPh = overview.putArray("placeholders");
        ObjectNode ph1 = overviewPh.addObject();
        ph1.put("key", "summary");
        ph1.put("prompt", "One paragraph summary of what this module does.");
        ph1.put("required", true);
        ph1.put("maxWords", 80);

        ObjectNode surface = sections.addObject();
        surface.put("id", "api-surface");
        surface.put("heading", "API surface");
        surface.put("level", 2);
        ArrayNode surfacePh = surface.putArray("placeholders");
        ObjectNode ph2 = surfacePh.addObject();
        ph2.put("key", "endpoints");
        ph2.put("prompt", "List the public endpoints exposed by this module.");
        ph2.put("binding", "code.route");

        ArrayNode vars = root.putArray("variables");
        ObjectNode v1 = vars.addObject();
        v1.put("key", "moduleName");
        v1.put("default", "core");
        v1.put("description", "Module under documentation.");

        return root;
    }

    public List<Map<String, String>> placeholderFieldDocs() {
        return List.of(
                Map.of("field", "key", "type", "string",
                        "description", "Stable identifier — referenced from {{variables}}."),
                Map.of("field", "prompt", "type", "string",
                        "description", "Instruction shown to the AI model."),
                Map.of("field", "required", "type", "boolean",
                        "description", "If true, the AI must populate this placeholder."),
                Map.of("field", "maxWords", "type", "integer",
                        "description", "Soft limit; the AI will be told to stay under this."),
                Map.of("field", "binding", "type", "string",
                        "description", "Optional AST binding — e.g. 'code.route', 'code.class'."));
    }

    /**
     * Normalise a free-form template body into the canonical shape. The
     * input can already be canonical (no-op), or a {@code sections: [...]}
     * flat list (legacy default in the UI), or a {@code placeholders: [...]}
     * flat list. Unknown top-level keys are preserved so we don't lose
     * future metadata.
     */
    public String normalise(String body) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (body == null || body.isBlank()) {
            return objectMapper.writeValueAsString(canonicalSample());
        }
        JsonNode root = objectMapper.readTree(body);
        ObjectNode out = root instanceof ObjectNode ? ((ObjectNode) root).deepCopy() : objectMapper.createObjectNode();
        if (!out.has("version")) out.put("version", 1);
        if (!out.has("sections") || !out.get("sections").isArray()) {
            // Legacy: { sections: [...] } without placeholder nesting.
            if (root.has("sections") && root.get("sections").isArray()) {
                ArrayNode wrapped = objectMapper.createArrayNode();
                for (JsonNode s : root.get("sections")) {
                    if (s instanceof ObjectNode so) {
                        ObjectNode sec = so.deepCopy();
                        if (!sec.has("placeholders")) {
                            sec.set("placeholders", objectMapper.createArrayNode());
                        }
                        wrapped.add(sec);
                    } else {
                        wrapped.add(s);
                    }
                }
                out.set("sections", wrapped);
            } else if (root.has("placeholders") && root.get("placeholders").isArray()) {
                // Convert legacy flat placeholders into a single section.
                ObjectNode section = objectMapper.createObjectNode();
                section.put("id", "main");
                section.put("heading", "Main");
                section.put("level", 2);
                section.set("placeholders", root.get("placeholders"));
                ArrayNode arr = objectMapper.createArrayNode();
                arr.add(section);
                out.set("sections", arr);
            } else {
                out.set("sections", objectMapper.createArrayNode());
            }
        } else {
            // Already canonical: ensure each section has a placeholders array.
            ArrayNode normalised = objectMapper.createArrayNode();
            for (JsonNode s : out.get("sections")) {
                if (s instanceof ObjectNode so) {
                    if (!so.has("placeholders") || !so.get("placeholders").isArray()) {
                        so.set("placeholders", objectMapper.createArrayNode());
                    }
                    normalised.add(so);
                } else {
                    normalised.add(s);
                }
            }
            out.set("sections", normalised);
        }
        return objectMapper.writeValueAsString(out);
    }

    /**
     * Strict validation. Throws {@link BadRequestException} with a
     * human-readable message when the body does not match the canonical
     * shape.
     */
    public void validate(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            throw new BadRequestException("Template body is required");
        }
        JsonNode tree;
        try {
            tree = objectMapper.readTree(bodyJson);
        } catch (Exception e) {
            throw new BadRequestException("Template body is not valid JSON: " + e.getMessage());
        }
        if (tree == null || !tree.isObject()) {
            throw new BadRequestException("Template body must be a JSON object");
        }
        JsonNode sections = tree.get("sections");
        if (sections == null || !sections.isArray()) {
            throw new BadRequestException("Template body must contain a 'sections' array");
        }
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        for (JsonNode section : sections) {
            if (section == null || !section.isObject()) {
                throw new BadRequestException("Each section must be an object");
            }
            JsonNode id = section.get("id");
            if (id == null || !id.isTextual() || id.asText().isBlank()) {
                throw new BadRequestException("Each section needs a non-empty 'id'");
            }
            if (!seenIds.add(id.asText())) {
                throw new BadRequestException("Duplicate section id: " + id.asText());
            }
            JsonNode heading = section.get("heading");
            if (heading == null || !heading.isTextual() || heading.asText().isBlank()) {
                throw new BadRequestException("Section '" + id.asText() + "' needs a 'heading'");
            }
            JsonNode level = section.get("level");
            if (level != null && (!level.isInt() || level.asInt() < 1 || level.asInt() > 6)) {
                throw new BadRequestException("Section '" + id.asText() + "' level must be 1..6");
            }
            JsonNode ph = section.get("placeholders");
            if (ph != null && !ph.isArray()) {
                throw new BadRequestException("Section '" + id.asText() + "' placeholders must be an array");
            }
            if (ph != null) {
                for (JsonNode p : ph) {
                    if (p == null || !p.isObject()) {
                        throw new BadRequestException("Each placeholder must be an object");
                    }
                    JsonNode key = p.get("key");
                    if (key == null || !key.isTextual() || key.asText().isBlank()) {
                        throw new BadRequestException("Placeholder in section '" + id.asText() + "' needs a 'key'");
                    }
                    if (!seenKeys.add(id.asText() + "::" + key.asText())) {
                        throw new BadRequestException("Duplicate placeholder key '" + key.asText() +
                                "' within section '" + id.asText() + "'");
                    }
                }
            }
        }
    }
}