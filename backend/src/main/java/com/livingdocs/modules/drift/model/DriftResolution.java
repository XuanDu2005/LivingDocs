package com.livingdocs.modules.drift.model;

/**
 * Lifecycle of a drift alert.
 *
 * <ul>
 *     <li>{@link #OPEN} — newly detected, awaiting human review.</li>
 *     <li>{@link #ACCEPTED} — reviewer accepted the suggested update.</li>
 *     <li>{@link #DISMISSED} — reviewer dismissed the alert as a false
 *         positive or non-issue.</li>
 *     <li>{@link #FIXED} — corresponding document version has been
 *         published, alert is resolved.</li>
 * </ul>
 */
public enum DriftResolution {
    OPEN,
    ACCEPTED,
    DISMISSED,
    FIXED
}