package com.livingdocs.modules.graph.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.graph.model.DependencyGraphResponse;
import com.livingdocs.modules.graph.model.ImpactAnalysisResponse;
import com.livingdocs.modules.graph.service.DependencyGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The documentation-to-code dependency graph and the impact analysis.
 *
 * <p>Both surfaces are gated behind {@code DEPENDENCY_GRAPH_VIEW}: the
 * graph shows the whole documentation structure of a workspace, which
 * is an oversight surface rather than an authoring one.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
@Tag(name = "Dependency graph", description = "Documentation-to-code dependency graph and impact analysis")
public class DependencyGraphController {

    private final DependencyGraphService graphService;

    public DependencyGraphController(DependencyGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * The dependency graph of a workspace.
     *
     * <p>Without a {@code documentId} the whole workspace is returned
     * (up to the node cap of the service); with one, the graph is
     * narrowed to that document and its neighbourhood.
     *
     * @param repositoryId restrict the graph to one repository
     * @param documentId  restrict the graph to one document
     * @param depth       how many hops are followed (1–3, default 2)
     */
    @GetMapping("/dependency-graph")
    @RequirePermission({"DEPENDENCY_GRAPH_VIEW"})
    @Operation(summary = "Get the documentation-to-code dependency graph of a workspace")
    public ResponseEntity<DependencyGraphResponse> graph(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) Integer depth) {
        return ResponseEntity.ok(graphService.graph(
                CurrentUser.requireId(), workspaceId, repositoryId, documentId, depth));
    }

    /**
     * The impact of changing one code entity.
     *
     * <p>Answers the question a reviewer of a pull request actually
     * has: "this method changes — which documentation does that
     * invalidate?"
     */
    @GetMapping("/code-entities/{entityId}/impact")
    @RequirePermission({"DEPENDENCY_GRAPH_VIEW"})
    @Operation(summary = "Get the impact analysis of a code entity")
    public ResponseEntity<ImpactAnalysisResponse> impact(
            @PathVariable UUID workspaceId,
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(graphService.impact(
                CurrentUser.requireId(), workspaceId, entityId));
    }
}
