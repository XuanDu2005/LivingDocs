package com.livingdocs.modules.template.model;

/**
 * Canonical documentation types that templates can target. Keeping them
 * in one place lets the UI render a controlled dropdown (instead of a
 * free-text field) and gives the AI service a small known set of
 * generation prompts to pick from.
 */
public enum DocType {
    MODULE_GUIDE("A high-level walkthrough of a single module / package."),
    API_REFERENCE("Endpoint-by-endpoint reference, usually auto-derived from routes/handlers."),
    README("Project root README — quickstart, installation, contributing."),
    ARCHITECTURE("System architecture overview — components, data flow, boundaries."),
    ADR("Architecture Decision Record — context, decision, consequences."),
    CHANGELOG("Chronological list of changes."),
    RUNBOOK("Operational guide — how to debug / recover from incidents."),
    DATA_DICTION("Diction of data fields and their semantics."),
    TUTORIAL("Step-by-step learning-oriented content."),
    CUSTOM("Anything else — falls through to the AI's judgement.");

    private final String description;

    DocType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static DocType parse(String raw) {
        if (raw == null || raw.isBlank()) return CUSTOM;
        try { return DocType.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return CUSTOM; }
    }

    /** Back-compat alias used by other modules. */
    public static DocType fromString(String raw) {
        return parse(raw);
    }
}