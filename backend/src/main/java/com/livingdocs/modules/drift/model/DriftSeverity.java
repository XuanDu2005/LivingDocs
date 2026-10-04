package com.livingdocs.modules.drift.model;

/**
 * Severity of a drift alert. Drives the visual indicators on the UI and
 * the merge-policy gates on the CI/CD side.
 */
public enum DriftSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}