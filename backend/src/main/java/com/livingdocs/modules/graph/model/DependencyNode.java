package com.livingdocs.modules.graph.model;

import java.util.Map;

/**
 * A node of the documentation-to-code dependency graph.
 *
 * <p>The graph is not a generic graph: it has exactly four kinds of
 * participant, and every one of them answers a different question of
 * the reader.
 *
 * <ul>
 *   <li>{@code DOCUMENT} — a piece of documentation.</li>
 *   <li>{@code CODE_ENTITY} — a class / method / function extracted
 *       from the source code.</li>
 *   <li>{@code MODULE} — a package (directory) inside a repository.</li>
 *   <li>{@code REPOSITORY} — a connected GitHub repository.</li>
 * </ul>
 *
 * <p>The {@code id} is namespaced by kind ({@code document:…},
 * {@code entity:…}, {@code module:…}, {@code repository:…}) so the
 * frontend can use it as a DOM/SVG key without having to know the kind
 * first.
 *
 * @param id       the namespaced identifier of the node
 * @param kind     one of DOCUMENT, CODE_ENTITY, MODULE, REPOSITORY
 * @param label    the display name of the node
 * @param subLabel the secondary line (file path, doc type, …)
 * @param meta     additional key/value facts about the node
 */
public record DependencyNode(
        String id,
        String kind,
        String label,
        String subLabel,
        Map<String, String> meta
) {
    /** The kinds of node the graph can contain. */
    public static final class Kind {
        public static final String DOCUMENT = "DOCUMENT";
        public static final String CODE_ENTITY = "CODE_ENTITY";
        public static final String MODULE = "MODULE";
        public static final String REPOSITORY = "REPOSITORY";

        private Kind() {
            // constants only
        }
    }

    /**
     * Build the namespaced id of a node.
     *
     * <p>The namespace matters: a document and a code entity have
     * distinct UUIDs, but the graph merges them into one flat id
     * space, and a reader (or the SVG renderer) has to be able to tell
     * them apart without a second lookup.
     */
    public static String id(String kind, java.util.UUID rawId) {
        return prefix(kind) + ":" + rawId;
    }

    /**
     * The id of a node whose natural key is not a UUID.
     *
     * <p>A module is identified by its repository plus its path
     * prefix, so its id has to carry both — otherwise two modules of
     * two repositories with the same path would collapse into one
     * node.
     */
    public static String id(String kind, String rawId) {
        return prefix(kind) + ":" + rawId;
    }

    private static String prefix(String kind) {
        return switch (kind) {
            case Kind.DOCUMENT -> "document";
            case Kind.CODE_ENTITY -> "entity";
            case Kind.MODULE -> "module";
            case Kind.REPOSITORY -> "repository";
            default -> kind.toLowerCase(java.util.Locale.ROOT);
        };
    }
}
