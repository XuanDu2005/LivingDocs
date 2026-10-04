package com.livingdocs.modules.template.model;

/**
 * Canonical documentation types supported by LivingDocs.
 *
 * <p>Templates are tagged with one of these values so the system can
 * pick a sensible default template when a document is created.
 */
public enum DocType {
    README("README"),
    API_REFERENCE("API Reference"),
    MODULE_GUIDE("Module Guide"),
    ONBOARDING_GUIDE("Onboarding Guide"),
    ADR("Architecture Decision Record"),
    CHANGELOG("Changelog"),
    CUSTOM("Custom");

    private final String displayName;

    DocType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static DocType fromString(String raw) {
        if (raw == null) return CUSTOM;
        try {
            return DocType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CUSTOM;
        }
    }
}