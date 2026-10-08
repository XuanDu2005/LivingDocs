import CoverageGapsPanel from './CoverageGapsPanel';
import { useCallback, useEffect, useState } from 'react';
import {
  GitBranch,
  GitPullRequest,
  Loader2,
  Plus,
  RefreshCw,
  Trash2,
  X,
} from 'lucide-react';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from './ui/card';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { LoadingState, ErrorState, EmptyState } from './ui/states';
import { describeError } from '../services/auth';
import {
  githubApi,
  GithubRepositoryCatalogItem,
  PullRequest,
  Repository,
} from '../services/github';
import { format } from 'date-fns';

interface RepositoriesPanelProps {
  workspaceId: string;
  canManage: boolean;
}

const STATE_VARIANT: Record<PullRequest['state'], 'success' | 'muted' | 'info'> = {
  OPEN: 'success',
  CLOSED: 'muted',
  MERGED: 'info',
};

const STATE_LABEL: Record<PullRequest['state'], string> = {
  OPEN: 'Open',
  CLOSED: 'Closed',
  MERGED: 'Merged',
};

const STATUS_VARIANT: Record<Repository['status'], 'success' | 'muted' | 'destructive' | 'warning'> = {
  CONNECTED: 'success',
  ARCHIVED: 'muted',
  ERROR: 'destructive',
  DISCONNECTED: 'warning',
};

export function RepositoriesPanel({ workspaceId, canManage }: RepositoriesPanelProps) {
  const [repos, setRepos] = useState<Repository[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [showCatalog, setShowCatalog] = useState<boolean>(false);
  const [catalog, setCatalog] = useState<GithubRepositoryCatalogItem[]>([]);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [catalogLoading, setCatalogLoading] = useState<boolean>(false);
  const [busy, setBusy] = useState<boolean>(false);
  const [selectedRepo, setSelectedRepo] = useState<Repository | null>(null);
  const [pulls, setPulls] = useState<PullRequest[]>([]);
  const [pullsLoading, setPullsLoading] = useState<boolean>(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await githubApi.listRepositories(workspaceId);
      setRepos(list);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openCatalog() {
    setShowCatalog(true);
    if (catalog.length > 0) return;
    setCatalogLoading(true);
    setCatalogError(null);
    try {
      const items = await githubApi.listCatalog();
      const connectedIds = new Set(repos.map((r) => r.githubId));
      setCatalog(items.filter((i) => !connectedIds.has(i.githubId)));
    } catch (err) {
      setCatalogError(describeError(err));
    } finally {
      setCatalogLoading(false);
    }
  }

  async function connect(githubId: number, defaultBranch?: string) {
    setBusy(true);
    setError(null);
    try {
      await githubApi.connectRepository(workspaceId, githubId, defaultBranch);
      setShowCatalog(false);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function disconnect(repo: Repository) {
    if (!window.confirm(`Disconnect ${repo.fullName}?`)) return;
    setBusy(true);
    try {
      await githubApi.disconnectRepository(workspaceId, repo.id);
      if (selectedRepo?.id === repo.id) {
        setSelectedRepo(null);
        setPulls([]);
      }
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function sync(repo: Repository) {
    setBusy(true);
    try {
      const updated = await githubApi.syncRepository(workspaceId, repo.id);
      setRepos((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function loadPulls(repo: Repository) {
    setSelectedRepo(repo);
    setPullsLoading(true);
    try {
      const list = await githubApi.listPullRequests(workspaceId, repo.id);
      setPulls(list);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setPullsLoading(false);
    }
  }

  async function ingest() {
    if (!selectedRepo) return;
    setBusy(true);
    try {
      const list = await githubApi.ingestPullRequests(workspaceId, selectedRepo.id, 'all');
      setPulls(list);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2 text-base">
              <GitBranch className="h-4 w-4" />
              Repositories
              <Badge variant="muted">{repos.length}</Badge>
            </CardTitle>
            <CardDescription>Connect GitHub repositories to enable code ingestion.</CardDescription>
          </div>
          {canManage && !showCatalog && (
            <Button size="sm" onClick={() => void openCatalog()}>
              <Plus className="mr-1 h-4 w-4" /> Connect
            </Button>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {error && <ErrorState message={error} />}

        {showCatalog && (
          <div className="rounded-md border bg-muted/20 p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-semibold">GitHub catalog</span>
              <Button size="sm" variant="ghost" onClick={() => setShowCatalog(false)}>
                <X className="h-4 w-4" />
              </Button>
            </div>
            {catalogLoading && <LoadingState message="Loading catalog…" />}
            {catalogError && <ErrorState message={catalogError} />}
            {!catalogLoading && catalog.length === 0 && !catalogError && (
              <p className="text-sm text-muted-foreground">
                No more repositories available to connect.
              </p>
            )}
            <div className="space-y-2">
              {catalog.map((item) => (
                <div
                  key={item.githubId}
                  className="flex items-center justify-between rounded-md border bg-card px-3 py-2"
                >
                  <div>
                    <div className="flex items-center gap-2 text-sm font-medium">
                      {item.fullName}
                      {item.isPrivate && <Badge variant="muted">private</Badge>}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {item.description ?? 'No description'}
                    </div>
                  </div>
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() => void connect(item.githubId, item.defaultBranch)}
                  >
                    {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Connect'}
                  </Button>
                </div>
              ))}
            </div>
          </div>
        )}

        {loading ? (
          <LoadingState message="Loading repositories…" />
        ) : repos.length === 0 ? (
          <EmptyState
            title="No repositories connected"
            description="Connect a GitHub repository to start indexing its code."
            action={
              canManage ? (
                <Button onClick={() => void openCatalog()}>
                  <Plus className="mr-1 h-4 w-4" /> Connect repository
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="space-y-2">
            {repos.map((repo) => (
              <div
                key={repo.id}
                className="flex items-center justify-between rounded-md border bg-muted/30 px-3 py-2"
              >
                <div>
                  <div className="flex items-center gap-2 text-sm font-medium">
                    {repo.fullName}
                    <Badge variant={STATUS_VARIANT[repo.status]}>{repo.status}</Badge>
                    {repo.isPrivate && <Badge variant="muted">private</Badge>}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    default branch: <code>{repo.defaultBranch}</code> ·{' '}
                    {repo.lastSyncedAt
                      ? `synced ${format(new Date(repo.lastSyncedAt), 'MMM d, HH:mm')}`
                      : 'never synced'}
                  </div>
                </div>
                <div className="flex gap-1">
                  <Button size="sm" variant="outline" onClick={() => void loadPulls(repo)}>
                    <GitPullRequest className="h-4 w-4" />
                  </Button>
                  {canManage && (
                    <>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void sync(repo)}
                        disabled={busy}
                      >
                        <RefreshCw className="h-4 w-4" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void disconnect(repo)}
                        disabled={busy}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}

        {selectedRepo && (
          <div className="rounded-md border bg-muted/20 p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-semibold">
                Pull requests for {selectedRepo.fullName}
              </span>
              <div className="flex gap-1">
                {canManage && (
                  <Button size="sm" variant="outline" onClick={() => void ingest()} disabled={busy}>
                    {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Ingest from GitHub'}
                  </Button>
                )}
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setSelectedRepo(null);
                    setPulls([]);
                  }}
                >
                  <X className="h-4 w-4" />
                </Button>
              </div>
            </div>
            {pullsLoading ? (
              <LoadingState message="Loading pull requests…" />
            ) : pulls.length === 0 ? (
              <p className="text-sm text-muted-foreground">No pull requests stored locally yet.</p>
            ) : (
              <div className="space-y-2">
                {pulls.map((pr) => (
                  <div
                    key={pr.id}
                    className="flex items-center justify-between rounded-md border bg-card px-3 py-2"
                  >
                    <div>
                      <div className="flex items-center gap-2 text-sm font-medium">
                        #{pr.number}
                        <Badge variant={STATE_VARIANT[pr.state]}>{STATE_LABEL[pr.state]}</Badge>
                        {pr.isDraft && <Badge variant="muted">draft</Badge>}
                      </div>
                      <div className="text-sm">{pr.title}</div>
                      <div className="text-xs text-muted-foreground">
                        {pr.headBranch} → {pr.baseBranch} · {pr.authorLogin ?? 'unknown'} ·
                        updated {format(new Date(pr.updatedAt), 'MMM d, HH:mm')}
                      </div>
                    </div>
                    {pr.htmlUrl && (
                      <a
                        href={pr.htmlUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="text-sm text-primary hover:underline"
                      >
                        View on GitHub ↗
                      </a>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
        {selectedRepo && (
          <CoverageGapsPanel workspaceId={workspaceId!} repositoryId={selectedRepo.id} />
        )}
      </CardContent>
    </Card>
  );
}
