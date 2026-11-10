package com.livingdocs.modules.template.model;

/**
 * Format the document render pipeline should produce from a template.
 *
 * <p>{@link #MARKDOWN} is the canonical target: the whole platform
 * (review sections, template compliance, diffs, the GitHub commit
 * path) treats a document body as markdown.
 *
 * <p>{@link #HTML} is supported by
 * {@link com.livingdocs.modules.template.service.TemplateRenderer}: the
 * markdown the AI produced is converted into HTML.
 *
 * <p>{@link #PDF} is <b>not implemented yet</b>. It is kept in the enum
 * because the column is already part of the schema and a future
 * renderer can pick it up without another migration. Selecting it does
 * not produce a PDF — the renderer falls back to markdown and says so
 * in the log — and the UI disables the option with a tooltip.
 *
 * <p>Persisted as plain text in {@code doc_templates.output_format}.
 */
public enum OutputFormat {

    /** The markdown the AI produced, stored verbatim. */
    MARKDOWN,

    /** The markdown converted to HTML. */
    HTML,

    /** Reserved for a future PDF renderer — not supported today. */
    PDF;

    /**
     * Is this format actually implemented by the renderer?
     *
     * <p>The UI uses this to disable the unsupported options, and the
     * renderer uses it to decide whether it has to fall back.
     */
    public boolean isSupported() {
        return this != PDF;
    }
}
