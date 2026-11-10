import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Boxes,
  Code2,
  FileText,
  GitBranch,
} from 'lucide-react';
import { Badge } from './ui/badge';
import { cn } from '../lib/utils';
import {
  DependencyEdge,
  DependencyGraph,
  DependencyNode,
} from '../services/dependencyGraph';

/**
 * The dependency graph, rendered as a two-level tree.
 *
 * <p>The rendering is deliberately plain CSS/SVG rather than a graph
 * library (react-flow, d3, …): the bundle is already ~1.9 MB and
 * carries a size warning, and the shape this needs is a tree, not a
 * general graph.
 *
 * <p>The shape is:
 *
 * <pre>
 *   Document ──documents──▶ Code entity ──contained in──▶ Module ──▶ Repository
 * </pre>
 *
 * <p>so the reader always sees a document on the left, the code it
 * documents in the middle, and the module / repository on the right.
 */

interface DependencyGraphPanelProps {
  graph: DependencyGraph;
  workspaceId: string;
  /**
   * Called when a code entity is clicked (used to open the impact panel).
   *
   * <p>The id handed over is the <em>raw</em> entity id, not the
   * namespaced node id: the impact endpoint addresses a code entity by
   * its UUID.
   */
  onSelectEntity?: (entityId: string) => void;
  /** The entity whose impact is currently shown, if any (a raw id). */
  selectedEntityId?: string | null;
  className?: string;
}

/** A document with the entities it documents. */
interface DocumentBranch {
  document: DependencyNode;
  entities: DependencyNode[];
}

/**
 * The raw id of a node, without its namespace.
 *
 * <p>The graph addresses its participants by a namespaced id
 * ({@code entity:…}) so the frontend can use it as a key without a
 * second lookup. The impact endpoint, on the other hand, addresses a
 * code entity by its plain UUID — so the namespace has to be stripped
 * again before the id is put into a URL.
 */
function rawId(node: DependencyNode): string {
  const separator = node.id.indexOf(':');
  return separator === -1 ? node.id : node.id.substring(separator + 1);
}

export function DependencyGraphPanel({
  graph,
  workspaceId,
  onSelectEntity,
  selectedEntityId,
  className,
}: DependencyGraphPanelProps) {
  const { t } = useTranslation();

  const { branches, modulesByEntity, repositoryByEntity } = useMemo(
    () => buildBranches(graph),
    [graph],
  );

  if (branches.length === 0) {
    return (
      <p className="px-4 py-6 text-sm text-muted-foreground">
        {t('dependencyGraph.empty', 'No documentation is linked to any code yet.')}
      </p>
    );
  }

  return (
    <div className={cn('space-y-3 p-4', className)}>
      {branches.map((branch) => (
        <div key={branch.document.id} className="rounded-lg border bg-card/40">
          {/* ── Level 1: the document ── */}
          <div className="flex items-start gap-2 border-b p-3">
            <FileText className="mt-0.5 h-4 w-4 shrink-0 text-blue-500" />
            <div className="min-w-0 flex-1">
              <Link
                to={`/workspaces/${workspaceId}/documents/${rawId(branch.document)}`}
                className="truncate text-sm font-medium hover:underline"
              >
                {branch.document.label}
              </Link>
              <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
                {branch.document.subLabel && (
                  <Badge variant="muted" className="text-[10px]">
                    {branch.document.subLabel}
                  </Badge>
                )}
                {branch.document.meta?.status && (
                  <Badge variant="muted" className="text-[10px]">
                    {branch.document.meta.status}
                  </Badge>
                )}
                <span className="text-[11px] text-muted-foreground">
                  {t('dependencyGraph.entityCount', '{{count}} code entities', {
                    count: branch.entities.length,
                  })}
                </span>
              </div>
            </div>
          </div>

          {/* ── Level 2: the entities the document documents ── */}
          {branch.entities.length === 0 ? (
            <p className="px-6 py-3 text-xs text-muted-foreground">
              {t('dependencyGraph.noEntities', 'No code entities linked to this document.')}
            </p>
          ) : (
            <ul className="divide-y">
              {branch.entities.map((entity) => {
                const module = modulesByEntity.get(entity.id);
                const repository = module
                  ? repositoryByEntity.get(module.id)
                  : repositoryByEntity.get(entity.id);
                const isSelected = selectedEntityId === rawId(entity);
                return (
                  <li key={entity.id}>
                    <div className="flex items-start gap-2 px-3 py-2">
                      {/* The connector: the edge from the document down to the entity. */}
                      <svg
                        width="12"
                        height="24"
                        viewBox="0 0 12 24"
                        aria-hidden="true"
                        className="mt-0.5 shrink-0"
                      >
                        <path
                          d="M1 0 V12 Q1 24 12 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="1.5"
                          className="text-muted-foreground/50"
                        />
                      </svg>

                      <Code2
                        className={cn(
                          'mt-0.5 h-4 w-4 shrink-0',
                          isSelected ? 'text-emerald-500' : 'text-emerald-500/70',
                        )}
                      />
                      <div className="min-w-0 flex-1">
                        {onSelectEntity ? (
                          <button
                            type="button"
                            onClick={() => onSelectEntity(rawId(entity))}
                            className={cn(
                              'truncate text-left text-sm hover:underline',
                              isSelected && 'font-semibold text-emerald-600 dark:text-emerald-400',
                            )}
                            title={entity.meta?.qualifiedName ?? entity.label}
                          >
                            {entity.label}
                          </button>
                        ) : (
                          <span className="truncate text-sm">{entity.label}</span>
                        )}
                        <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
                          {entity.subLabel && (
                            <span className="truncate font-mono">{entity.subLabel}</span>
                          )}
                          {entity.meta?.entityType && (
                            <Badge variant="muted" className="text-[10px]">
                              {entity.meta.entityType}
                            </Badge>
                          )}
                          {module && (
                            <span className="flex items-center gap-1">
                              <Boxes className="h-3 w-3" />
                              {module.label}
                            </span>
                          )}
                          {repository && (
                            <span className="flex items-center gap-1">
                              <GitBranch className="h-3 w-3" />
                              {repository.label}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      ))}

      <p className="px-1 text-[11px] text-muted-foreground">
        {t(
          'dependencyGraph.legend',
          '{{documents}} documents · {{entities}} code entities · {{modules}} modules · {{repositories}} repositories',
          {
            documents: graph.nodes.filter((n) => n.kind === 'DOCUMENT').length,
            entities: graph.nodes.filter((n) => n.kind === 'CODE_ENTITY').length,
            modules: graph.nodes.filter((n) => n.kind === 'MODULE').length,
            repositories: graph.nodes.filter((n) => n.kind === 'REPOSITORY').length,
          },
        )}
      </p>
    </div>
  );
}

/**
 * Turn the flat node/edge lists into the tree the panel renders.
 *
 * <p>The graph is walked from the documents: for each document, the
 * entities it links to. The containment chain runs the other way
 * (repository → module → entity), so it is walked upwards from the
 * entity.
 */
function buildBranches(graph: DependencyGraph): {
  branches: DocumentBranch[];
  modulesByEntity: Map<string, DependencyNode>;
  repositoryByEntity: Map<string, DependencyNode>;
} {
  const nodesById = new Map<string, DependencyNode>();
  for (const node of graph.nodes) {
    nodesById.set(node.id, node);
  }

  // The outgoing edges, keyed by their source.
  const outgoing = new Map<string, DependencyEdge[]>();
  for (const edge of graph.edges) {
    const list = outgoing.get(edge.from);
    if (list) {
      list.push(edge);
    } else {
      outgoing.set(edge.from, [edge]);
    }
  }

  const modulesByEntity = new Map<string, DependencyNode>();
  const repositoryByEntity = new Map<string, DependencyNode>();

  // The containment chain of one entity: its module and its
  // repository. The edges point from the container to the contained
  // (repository → module → entity), so the chain is found by walking
  // the incoming edges upwards.
  const incoming = new Map<string, DependencyEdge[]>();
  for (const edge of graph.edges) {
    if (edge.kind !== 'CONTAINS') continue;
    const list = incoming.get(edge.to);
    if (list) {
      list.push(edge);
    } else {
      incoming.set(edge.to, [edge]);
    }
  }

  const resolveContainment = (entityId: string) => {
    for (const edge of incoming.get(entityId) ?? []) {
      const container = nodesById.get(edge.from);
      if (!container) continue;
      if (container.kind === 'MODULE') {
        modulesByEntity.set(entityId, container);
        for (const repoEdge of incoming.get(container.id) ?? []) {
          const repo = nodesById.get(repoEdge.from);
          if (repo && repo.kind === 'REPOSITORY') {
            repositoryByEntity.set(container.id, repo);
            repositoryByEntity.set(entityId, repo);
          }
        }
      } else if (container.kind === 'REPOSITORY') {
        repositoryByEntity.set(entityId, container);
      }
    }
  };

  const branches: DocumentBranch[] = [];

  for (const node of graph.nodes) {
    if (node.kind !== 'DOCUMENT') continue;

    const entities: DependencyNode[] = [];
    for (const edge of outgoing.get(node.id) ?? []) {
      const target = nodesById.get(edge.to);
      if (!target) continue;
      if (target.kind === 'CODE_ENTITY') {
        entities.push(target);
        resolveContainment(target.id);
      }
    }

    branches.push({ document: node, entities });
  }

  return { branches, modulesByEntity, repositoryByEntity };
}

export default DependencyGraphPanel;
