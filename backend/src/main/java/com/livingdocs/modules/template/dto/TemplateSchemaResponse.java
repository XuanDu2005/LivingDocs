package com.livingdocs.modules.template.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Returned by {@code GET /api/v1/template-schema}. Bundles everything
 * the visual builder needs without making a separate round-trip:
 * the canonical sample, the supported placeholders, and the list of
 * known documentation types.
 */
public record TemplateSchemaResponse(
        int canonicalVersion,
        JsonNode sample,
        List<PlaceholderFieldDoc> placeholderFields,
        List<DocTypeInfo> docTypes
) {
    public record PlaceholderFieldDoc(String field, String type, String description) {}

    public record DocTypeInfo(String value, String label, String description) {}
}