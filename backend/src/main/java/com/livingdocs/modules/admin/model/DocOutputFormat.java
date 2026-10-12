package com.livingdocs.modules.admin.model;

/**
 * Output formats available for generated documentation.
 *
 * <p>Mirrors the {@code doc_templates.output_format} enum so the
 * platform default can be enforced without surprise downgrades
 * (e.g. a global template locked to PDF cannot have its default
 * "promoted" to MARKDOWN).
 */
public enum DocOutputFormat {
    MARKDOWN,
    HTML,
    PDF
}
