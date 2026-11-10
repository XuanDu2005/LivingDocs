package com.livingdocs.modules.graph.model;

import java.util.List;
import java.util.UUID;

/**
 * The documentation-to-code dependency graph of a workspace.
 *
 * <p>This is the A5 deliverable: a graph that shows which
 * documentation depends on which source code, so that a change in the
 * code can be traced back to the documentation it invalidates.
 *
 * <p>The graph is deliberately flat — a list of nodes and a list of
 * edges — rather than a nested tree. The frontend decides how to lay
 * it out, and a flat list keeps the backend free of presentation
 * decisions (how many rows fit, whether to collapse a branch, …).
 *
 * @param workspaceId the workspace the graph belongs to
 * @param nodes       the participants of the graph
 * @param edges       the relations between the participants
 */
public record DependencyGraphResponse(
        UUID workspaceId,
        List<DependencyNode> nodes,
        List<DependencyEdge> edges
) {
}
