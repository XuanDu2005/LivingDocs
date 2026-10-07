package com.livingdocs.modules.ai.indexing;

/**
 * What the job is doing.
 *
 * <ul>
 *   <li>{@link #INDEX} — index exactly one document.</li>
 *   <li>{@link #REINDEX_ALL} — reindex every document in a workspace.</li>
 * </ul>
 */
public enum IndexJobKind {
    INDEX,
    REINDEX_ALL
}