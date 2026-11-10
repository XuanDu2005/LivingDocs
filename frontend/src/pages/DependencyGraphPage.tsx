import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, GitBranch, Radar, RefreshCw } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/select';
import { ErrorState, LoadingState } from '../components/ui/states';
import DependencyGraphPanel from '../components/DependencyGraphPanel';
import ImpactPanel from '../components/DependencyImpactPanel';
import { describeError } from '../services/auth';
import { getDependencyGraph, DependencyGraph } from '../services/dependencyGraph';
import { githubApi, Repository } from '../services/github';

/**
 * The documentation-to-code dependency graph of a workspace.
 *
 * <p>This is the A5 surface: "which documentation depends on which
 * code, and what breaks when the code changes?".
 *
 * <p>The page has two halves: the graph itself (left) and the impact
 * analysis of the selected code entity (right). Clicking an entity in
 * the graph fills the right half.
 */
export default function DependencyGraphPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  const [graph, setGraph] = useState<DependencyGraph | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [repositoryId, setRepositoryId] = useState<string>('ALL');
  const [depth, setDepth] = useState<number>(2);
  const [selectedEntityId, setSelectedEntityId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      setGraph(
        await getDependencyGraph(workspaceId, {
          repositoryId: repositoryId === 'ALL' ? null : repositoryId,
          depth,
        }),
      );
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId, repositoryId, depth]);

  useEffect(() => {
    void load();
  }, [load]);

  // The repositories of the workspace fill the filter.
  useEffect(() => {
    if (!workspaceId) return;
    let cancelled = false;
    void (async () => {
      const repos = await githubApi
        .listRepositories(workspaceId)
        .catch(() => [] as Repository[]);
      if (!cancelled) setRepositories(repos);
    })();
    return () => {
      cancelled = true;
    };
  }, [workspaceId]);

  const stats = useMemo(() => {
    if (!graph) return { documents: 0, entities: 0, modules: 0, repositories: 0 };
    return {
      documents: graph.nodes.filter((n) => n.kind === 'DOCUMENT').length,
      entities: graph.nodes.filter((n) => n.kind === 'CODE_ENTITY').length,
      modules: graph.nodes.filter((n) => n.kind === 'MODULE').length,
      repositories: graph.nodes.filter((n) => n.kind === 'REPOSITORY').length,
    };
  }, [graph]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/workspaces/${workspaceId}`}>
              <ArrowLeft className="mr-1 h-4 w-4" />
              {t('common.back')}
            </Link>
          </Button>
          <h1 className="mt-2 flex items-center gap-2 text-2xl font-bold tracking-tight">
            <Radar className="h-5 w-5" />
            {t('dependencyGraph.title', 'Dependency graph')}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t(
              'dependencyGraph.subtitle',
              'Which documentation depends on which code — and what a code change invalidates.',
            )}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => void load()}>
          <RefreshCw className="mr-1 h-4 w-4" />
          {t('common.refresh')}
        </Button>
      </div>

      {/* ── The filters ── */}
      <Card>
        <CardContent className="flex flex-wrap items-end gap-4 p-4">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">
              {t('dependencyGraph.filterRepository', 'Repository')}
            </label>
            <Select value={repositoryId} onValueChange={setRepositoryId}>
              <SelectTrigger className="w-[240px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">
                  {t('dependencyGraph.allRepositories', 'All repositories')}
                </SelectItem>
                {repositories.map((repo) => (
                  <SelectItem key={repo.id} value={repo.id}>
                    {repo.fullName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">
              {t('dependencyGraph.filterDepth', 'Depth')}
            </label>
            <Select value={String(depth)} onValueChange={(v) => setDepth(Number(v))}>
              <SelectTrigger className="w-[120px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {[1, 2, 3].map((value) => (
                  <SelectItem key={value} value={String(value)}>
                    {value}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-wrap gap-2">
            <Badge variant="muted" className="text-[10px]">
              {t('dependencyGraph.statDocuments', '{{count}} documents', {
                count: stats.documents,
              })}
            </Badge>
            <Badge variant="muted" className="text-[10px]">
              {t('dependencyGraph.statEntities', '{{count}} entities', {
                count: stats.entities,
              })}
            </Badge>
            <Badge variant="muted" className="text-[10px]">
              {t('dependencyGraph.statModules', '{{count}} modules', {
                count: stats.modules,
              })}
            </Badge>
            <Badge variant="muted" className="text-[10px]">
              {t('dependencyGraph.statRepositories', '{{count}} repositories', {
                count: stats.repositories,
              })}
            </Badge>
          </div>
        </CardContent>
      </Card>

      {/* ── The graph and the impact analysis ── */}
      <div className="grid gap-6 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <GitBranch className="h-4 w-4" />
              {t('dependencyGraph.graphTitle', 'Graph')}
            </CardTitle>
            <CardDescription>
              {t(
                'dependencyGraph.graphDesc',
                'Each document lists the code entities it documents; the module and the repository of each entity follow.',
              )}
            </CardDescription>
          </CardHeader>
          <CardContent className="p-0">
            {loading ? (
              <LoadingState message={t('dependencyGraph.loading', 'Loading the graph…')} />
            ) : error ? (
              <div className="p-4">
                <ErrorState message={error} />
              </div>
            ) : (
              <DependencyGraphPanel
                graph={graph!}
                workspaceId={workspaceId!}
                onSelectEntity={setSelectedEntityId}
                selectedEntityId={selectedEntityId}
              />
            )}
          </CardContent>
        </Card>

        <div className="lg:col-span-2">
          {selectedEntityId ? (
            <ImpactPanel
              workspaceId={workspaceId!}
              entityId={selectedEntityId}
              onClose={() => setSelectedEntityId(null)}
            />
          ) : (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <Radar className="h-4 w-4" />
                  {t('dependencyGraph.impactTitle', 'Impact analysis')}
                </CardTitle>
                <CardDescription>
                  {t(
                    'dependencyGraph.impactHint',
                    'Select a code entity in the graph to see which documentation it invalidates, what else moves with it, and which pull requests are open.',
                  )}
                </CardDescription>
              </CardHeader>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
