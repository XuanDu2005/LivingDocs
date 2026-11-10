/**
 * Dependency graph and impact analysis API client.
 *
 * <p>The graph is the A5 deliverable: a documentation-to-code
 * dependency graph that shows which documentation depends on which
 * source code, so that a code change can be traced back to the
 * documentation it invalidates.
 *
 * <p>The impact analysis is the more actionable of the two: it answers
 * "I am about to change this method — which documentation does that
 * invalidate, what else moves with it, and which pull requests are
 * open?".
 */
import apiClient from './api';

/** The kinds of node the graph can contain. */
export type DependencyNodeKind =
  | 'DOCUMENT'
  | 'CODE_ENTITY'
  | 'MODULE'
  | 'REPOSITORY';

/** The kinds of relation the graph can contain. */
export type DependencyEdgeKind = 'DOCUMENTS' | 'REFERENCES' | 'CONTAINS';

/**
 * One participant of the graph.
 *
 * <p>The `id` is namespaced by kind (`document:…`, `entity:…`,
 * `module:…`, `repository:…`) so it can be used as a DOM/SVG key
 * without a second lookup.
 */
export interface DependencyNode {
  id: string;
  kind: DependencyNodeKind;
  label: string;
  /** The secondary line: the file path of an entity, the doc type of a document. */
  subLabel: string | null;
  meta: Record<string, string>;
}

/** One relation between two participants. */
export interface DependencyEdge {
  from: string;
  to: string;
  kind: DependencyEdgeKind;
  /** The raw link kind of the underlying doc↔code link, when there is one. */
  linkKind: string | null;
  /** The confidence of the underlying link, when it has one. */
  confidence: number | null;
}

/** The dependency graph of a workspace. */
export interface DependencyGraph {
  workspaceId: string;
  nodes: DependencyNode[];
  edges: DependencyEdge[];
}

/** A document that documents the analysed entity. */
export interface ImpactedDocument {
  id: string;
  title: string;
  slug: string;
  docType: string;
  status: string | null;
  repositoryId: string | null;
  linkKind: string | null;
  confidence: number | null;
}

/** An entity that moves with the analysed one. */
export interface RelatedEntity {
  id: string;
  qualifiedName: string;
  entityType: string;
  filePath: string;
  startLine: number | null;
  /** `SAME_FILE`, `SAME_MODULE` or `SAME_REPOSITORY`. */
  relation: string;
}

/** A drift alert that touches one of the affected documents. */
export interface ImpactedDriftAlert {
  id: string;
  documentId: string;
  severity: string;
  driftKind: string;
  title: string;
  resolutionStatus: string;
  detectedAt: string;
}

/** A pull request currently open on the repository. */
export interface ImpactedPullRequest {
  id: string;
  number: number;
  title: string;
  state: string;
  authorLogin: string | null;
  headBranch: string;
  htmlUrl: string | null;
  updatedAt: string;
}

/** The impact of changing one code entity. */
export interface ImpactAnalysis {
  entity: {
    id: string;
    repositoryId: string;
    qualifiedName: string;
    simpleName: string;
    entityType: string;
    filePath: string;
    startLine: number | null;
    endLine: number | null;
    modulePath: string | null;
    language: string | null;
  };
  documents: ImpactedDocument[];
  relatedEntities: RelatedEntity[];
  driftAlerts: ImpactedDriftAlert[];
  pullRequests: ImpactedPullRequest[];
}

/**
 * Fetch the dependency graph of a workspace.
 *
 * <p>Requires the `DEPENDENCY_GRAPH_VIEW` permission and workspace
 * membership.
 *
 * @param workspaceId the workspace the graph belongs to
 * @param options optional filters: a repository, a document and the
 *                depth of the traversal (1–3, default 2)
 */
export async function getDependencyGraph(
  workspaceId: string,
  options: { repositoryId?: string | null; documentId?: string | null; depth?: number } = {},
): Promise<DependencyGraph> {
  const params: Record<string, string | number> = {};
  if (options.repositoryId) params.repositoryId = options.repositoryId;
  if (options.documentId) params.documentId = options.documentId;
  if (options.depth !== undefined) params.depth = options.depth;

  const { data } = await apiClient.get<DependencyGraph>(
    `/workspaces/${workspaceId}/dependency-graph`,
    { params },
  );
  return data;
}

/**
 * Fetch the impact analysis of a code entity.
 *
 * <p>Requires the `DEPENDENCY_GRAPH_VIEW` permission and workspace
 * membership.
 */
export async function getEntityImpact(
  workspaceId: string,
  entityId: string,
): Promise<ImpactAnalysis> {
  const { data } = await apiClient.get<ImpactAnalysis>(
    `/workspaces/${workspaceId}/code-entities/${entityId}/impact`,
  );
  return data;
}

export const dependencyGraphApi = {
  getGraph: getDependencyGraph,
  getImpact: getEntityImpact,
};
