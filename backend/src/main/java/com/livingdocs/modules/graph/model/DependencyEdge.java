package com.livingdocs.modules.graph.model;

/**
 * An edge of the documentation-to-code dependency graph.
 *
 * <p>Three kinds of relation carry the whole meaning of the graph:
 *
 * <ul>
 *   <li>{@code DOCUMENTS} — the document describes the code entity. This
 *       is the edge the requirement asks for: it is what makes a
 *       documentation-to-code dependency graph at all.</li>
 *   <li>{@code REFERENCES} — the document mentions the entity without
 *       claiming to document it (a link the extractor was less sure
 *       about).</li>
 *   <li>{@code CONTAINS} — the structural edge: a repository contains a
 *       module, a module contains a code entity.</li>
 * </ul>
 *
 * @param from       the id of the source node
 * @param to         the id of the target node
 * @param kind       one of DOCUMENTS, REFERENCES, CONTAINS
 * @param linkKind   the raw link kind of the underlying link, when the
 *                   edge is derived from a {@code code_document_links}
 *                   row (REFERENCE, GENERATED, …)
 * @param confidence the confidence of the underlying link, when there
 *                   is one
 */
public record DependencyEdge(
        String from,
        String to,
        String kind,
        String linkKind,
        Float confidence
) {
    /** The kinds of relation the graph can contain. */
    public static final class Kind {
        public static final String DOCUMENTS = "DOCUMENTS";
        public static final String REFERENCES = "REFERENCES";
        public static final String CONTAINS = "CONTAINS";

        private Kind() {
            // constants only
        }
    }
}
