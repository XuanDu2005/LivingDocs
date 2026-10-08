package com.livingdocs.modules.template.model;

/**
 * Format the document render pipeline should produce from a template.
 *
 * <p>{@link #MARKDOWN} is the only fully-implemented target today;
 * {@link #HTML} and {@link #PDF} are stored as metadata so the future
 * renderer can pick them up without another schema migration.
 *
 * <p>Persisted as plain text in {@code doc_templates.output_format}.
 */
public enum OutputFormat {
    MARKDOWN,
    HTML,
    PDF
}