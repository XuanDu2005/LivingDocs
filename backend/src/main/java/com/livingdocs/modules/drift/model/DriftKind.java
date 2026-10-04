package com.livingdocs.modules.drift.model;

/**
 * The category of documentation drift detected by the analyser.
 *
 * <ul>
 *     <li>{@link #REFERENTIAL} — a code element referenced in the
 *         documentation was renamed or deleted.</li>
 *     <li>{@link #SIGNATURE} — a referenced function / method signature
 *         changed (parameters, return type, exceptions).</li>
 *     <li>{@link #SEMANTIC} — a behaviour, contract or relationship changed
 *         while the referenced code elements still exist.</li>
 * </ul>
 */
public enum DriftKind {
    REFERENTIAL,
    SIGNATURE,
    SEMANTIC
}