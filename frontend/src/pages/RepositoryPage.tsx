import { FormEvent, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft,
  Github,
  GitBranch,
  GitCommit,
  RefreshCw,
  Unplug,
  ExternalLink,
  Search,
  X,
  AlertCircle,
  Loader2,
  GitPullRequest,
  CheckCircle2,
  Sparkles,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { ErrorState, EmptyState, LoadingState } from '../components/ui/states';
import { githubApi, Repository, PullRequest, Commit } from '../services/github';
import { describeError } from '../services/auth';

/**
 * Per-workspace repository browser.
 *
 * Responsibilities (Developer / Author):
 *   1. Browse the GitHub catalog (or paste a URL) and add a repository.
 *   2. See the connection status, default branch, last sync time.
 *   3. Manually re-sync a repository.
 *   4. View PRs and commits ingested from the repository.
 *   5. Disconnect a repository (revokes the link; GitHub side is untouched).
 *   6. Trigger an AI documentation generation sweep from the selected repo.
 */
export default function RepositoryPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  const [repos, setRepos] = useState<Repository[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const [search, setSearch] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'CONNECTED' | 'ERROR' | 'DISCONNECTED' | 'ARCHIVED'>('ALL');

  // Connect dialog state
  const [showConnect, setShowConnect] = useState<boolean>(false);
  const [tab, setTab] = useState<'catalog' | 'url'>('catalog');
  const [catalog, setCatalog] = useState<Awaited<ReturnType<typeof githubApi.listCatalog>>>([]);
  const [catalogLoading, setCatalogLoading] = useState<boolean>(false);
  const [catalogErr, setCatalogErr] = useState<string | null>(null);
  const [catalogSearch, setCatalogSearch] = useState<string>('');
  const [urlInput, setUrlInput] = useState<string>('');
  const [busy, setBusy] = useState<boolean>(false);
  const [githubConnected, setGithubConnected] = useState<boolean | null>(null);

  // Detail panel state
  const [selected, setSelected] = useState<Repository | null>(null);
  const [pulls, setPulls] = useState<PullRequest[]>([]);
  const [prsLoading, setPrsLoading] = useState<boolean>(false);
  const [commits, setCommits] = useState<Commit[]>([]);
  const [commitsLoading, setCommitsLoading] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<'pulls' | 'commits'>('pulls');

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const list = await githubApi.listRepositories(workspaceId);
      setRepos(list);
      if (selected && !list.find((r) => r.id === selected.id)) {
        setSelected(null);
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  async function checkConnection() {
    try {
      const conn = await githubApi.getConnection();
      setGithubConnected(!!conn && conn.status === 'ACTIVE');
    } catch {
      setGithubConnected(false);
    }
  }

  useEffect(() => {
    void load();
    void checkConnection();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  const filteredRepos = useMemo(() => {
    return repos.filter((r) => {
      if (statusFilter !== 'ALL' && r.status !== statusFilter) return false;
      if (search.trim()) {
        const q = search.toLowerCase();
        if (
          !r.fullName.toLowerCase().includes(q) &&
          !r.owner.toLowerCase().includes(q) &&
          !(r.description || '').toLowerCase().includes(q)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [repos, search, statusFilter]);

  async function openConnect() {
    if (!workspaceId) return;

    // Check if GitHub is connected first - if not, redirect to OAuth
    let isConnected = githubConnected;
    if (isConnected === null) {
      try {
        const conn = await githubApi.getConnection();
        isConnected = !!conn && conn.status === 'ACTIVE';
        setGithubConnected(isConnected);
      } catch {
        isConnected = false;
      }
    }

    if (!isConnected) {
      // Not connected - start OAuth flow immediately
      startGithubOAuth();
      return;
    }

    setShowConnect(true);
    setTab('catalog');
    setCatalogErr(null);
    setCatalogSearch('');
    if (catalog.length === 0) {
      try {
        setCatalogLoading(true);
        const list = await githubApi.listCatalog();
        setCatalog(list);
      } catch (err) {
        setCatalogErr(describeError(err));
      } finally {
        setCatalogLoading(false);
      }
    }
  }

  async function connectFromCatalog(githubId: number) {
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const repo = await githubApi.connectRepository(workspaceId, githubId);
      setInfo(t('repository.connected', { name: repo.fullName }));
      setShowConnect(false);
      await load();
      setSelected(repo);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function connectFromUrl() {
    if (!workspaceId || !urlInput.trim()) return;

    // Check OAuth first
    let isConnected = githubConnected;
    if (isConnected === null) {
      try {
        const conn = await githubApi.getConnection();
        isConnected = !!conn && conn.status === 'ACTIVE';
        setGithubConnected(isConnected);
      } catch {
        isConnected = false;
      }
    }

    if (!isConnected) {
      setError(t('repository.githubRequired', 'Please connect GitHub first.'));
      startGithubOAuth();
      return;
    }

    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const meta = await githubApi.getRepositoryByUrl(urlInput.trim());
      const repo = await githubApi.connectRepository(workspaceId, meta.githubId);
      setInfo(t('repository.connected', { name: repo.fullName }));
      setShowConnect(false);
      setUrlInput('');
      await load();
      setSelected(repo);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function syncRepo(repo: Repository) {
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const updated = await githubApi.syncRepository(workspaceId, repo.id);
      setInfo(t('repository.syncStarted', { name: updated.fullName }));
      await load();
      if (selected?.id === updated.id) {
        await loadDetails(updated);
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function disconnectRepo(repo: Repository) {
    if (!workspaceId) return;
    if (!window.confirm(t('repository.disconnectConfirm', { name: repo.fullName }))) return;
    setBusy(true);
    setError(null);
    try {
      await githubApi.disconnectRepository(workspaceId, repo.id);
      setInfo(t('repository.disconnected', { name: repo.fullName }));
      if (selected?.id === repo.id) setSelected(null);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function loadDetails(repo: Repository) {
    if (!workspaceId) return;
    setSelected(repo);
    setPulls([]);
    setCommits([]);
    setPrsLoading(true);
    setCommitsLoading(true);
    setActiveTab('pulls');
    try {
      const [prs, cs] = await Promise.all([
        githubApi.listPullRequests(workspaceId, repo.id),
        githubApi.listCommits(workspaceId, repo.id, 50),
      ]);
      setPulls(prs);
      setCommits(cs);
    } catch (err) {
      // non-critical
    } finally {
      setPrsLoading(false);
      setCommitsLoading(false);
    }
  }

  function startGithubOAuth() {
    void githubApi
      .getAuthorizeUrl()
      .then(({ url, state }) => {
        // Persist the CSRF state so the callback page can verify it.
        sessionStorage.setItem('github-oauth-state', state);
        window.location.href = url;
      })
      .catch((err) => setError(describeError(err)));
  }

  const filteredCatalog = useMemo(() => {
    if (!catalogSearch.trim()) return catalog;
    const q = catalogSearch.toLowerCase();
    return catalog.filter(
      (c) => c.fullName.toLowerCase().includes(q) || c.name.toLowerCase().includes(q),
    );
  }, [catalog, catalogSearch]);

  if (!workspaceId) return null;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <Github className="h-5 w-5" /> {t('repository.title')}
          </h1>
          <p className="text-sm text-muted-foreground">{t('repository.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          {githubConnected === false ? (
            <Button size="sm" onClick={startGithubOAuth}>
              <Github className="mr-1 h-4 w-4" /> {t('repository.connectGithub')}
            </Button>
          ) : (
            <Button size="sm" onClick={openConnect} disabled={busy}>
              <GitBranch className="mr-1 h-4 w-4" /> {t('repository.addRepo')}
            </Button>
          )}
        </div>
      </div>

      {githubConnected === false && (
        <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-700 dark:text-amber-300 flex items-start gap-3">
          <AlertCircle className="h-4 w-4 mt-0.5 shrink-0" />
          <div>
            <div className="font-medium">{t('repository.githubNotConnectedTitle')}</div>
            <p className="mt-1 text-amber-700/80 dark:text-amber-300/80">
              {t('repository.githubNotConnectedDesc')}
            </p>
          </div>
        </div>
      )}

      {error && <ErrorState message={error} />}
      {info && (
        <div className="rounded-md border border-green-500/40 bg-green-500/10 p-3 text-sm text-green-700 dark:text-green-300 flex items-center gap-2">
          <CheckCircle2 className="h-4 w-4" /> {info}
        </div>
      )}

      {/* Filters */}
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('repository.searchPlaceholder')}
            className="pl-8"
          />
          {search && (
            <Button
              variant="ghost"
              size="icon"
              className="absolute right-1 top-1/2 -translate-y-1/2 h-6 w-6"
              onClick={() => setSearch('')}
              aria-label="Clear"
            >
              <X className="h-3 w-3" />
            </Button>
          )}
        </div>
        <div className="flex gap-1 flex-wrap">
          {(['ALL', 'CONNECTED', 'ERROR', 'ARCHIVED', 'DISCONNECTED'] as const).map((s) => (
            <Button
              key={s}
              size="sm"
              variant={statusFilter === s ? 'default' : 'outline'}
              onClick={() => setStatusFilter(s)}
            >
              {t(`repository.filter${s.charAt(0)}${s.slice(1).toLowerCase()}`)}
            </Button>
          ))}
        </div>
      </div>

      {loading ? (
        <LoadingState message={t('repository.loading')} />
      ) : repos.length === 0 ? (
        // No repositories at all - check if GitHub is connected
        githubConnected === false ? (
          // Not connected to GitHub - show prominent CTA
          <div className="flex flex-col items-center justify-center py-16 px-4 text-center">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <Github className="h-8 w-8 text-muted-foreground" />
            </div>
            <h3 className="text-lg font-semibold mb-2">{t('repository.connectGithubFirstTitle', 'Connect GitHub to get started')}</h3>
            <p className="text-sm text-muted-foreground mb-6 max-w-md">
              {t('repository.connectGithubFirstDesc', 'You need to connect your GitHub account before you can add repositories to this workspace.')}
            </p>
            <Button size="lg" onClick={startGithubOAuth}>
              <Github className="mr-2 h-5 w-5" /> {t('repository.connectGithub', 'Connect GitHub')}
            </Button>
          </div>
        ) : (
          // Connected but no repos yet
          <EmptyState
            icon={<Github className="h-8 w-8" />}
            title={t('repository.emptyTitle')}
            description={t('repository.emptyDesc')}
            action={
              <Button onClick={openConnect}>
                <GitBranch className="mr-1 h-4 w-4" /> {t('repository.addRepo')}
              </Button>
            }
          />
        )
      ) : filteredRepos.length === 0 ? (
        // Has repos but no matches for filter
        <EmptyState
          icon={<Github className="h-8 w-8" />}
          title={t('repository.noMatchTitle')}
          description={t('repository.noMatchDesc')}
          action={null}
        />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {filteredRepos.map((r) => (
            <Card
              key={r.id}
              className={`group transition-shadow hover:shadow-md cursor-pointer ${
                selected?.id === r.id ? 'ring-2 ring-primary' : ''
              }`}
              onClick={() => loadDetails(r)}
            >
              <CardHeader>
                <div className="flex items-center justify-between gap-2">
                  <CardTitle className="text-base flex items-center gap-2 truncate">
                    <Github className="h-4 w-4 shrink-0" />
                    <span className="truncate">{r.fullName}</span>
                  </CardTitle>
                  <StatusBadge status={r.status} />
                </div>
                {r.description && (
                  <CardDescription className="line-clamp-2">{r.description}</CardDescription>
                )}
              </CardHeader>
              <CardContent className="space-y-2 text-xs text-muted-foreground">
                <div className="flex items-center gap-3 flex-wrap">
                  <span className="flex items-center gap-1">
                    <GitBranch className="h-3 w-3" /> {r.defaultBranch}
                  </span>
                  {r.isPrivate && <Badge variant="muted" className="text-[10px]">private</Badge>}
                  {r.htmlUrl && (
                    <a
                      href={r.htmlUrl}
                      target="_blank"
                      rel="noreferrer"
                      onClick={(e) => e.stopPropagation()}
                      className="flex items-center gap-1 hover:text-primary"
                    >
                      <ExternalLink className="h-3 w-3" /> {t('repository.openOnGithub')}
                    </a>
                  )}
                </div>
                <div>
                  {t('repository.lastSynced', {
                    when: r.lastSyncedAt
                      ? new Date(r.lastSyncedAt).toLocaleString()
                      : t('repository.never'),
                  })}
                </div>
                <div className="flex gap-1 pt-2" onClick={(e) => e.stopPropagation()}>
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-7 text-xs"
                    onClick={() => syncRepo(r)}
                    disabled={busy}
                  >
                    <RefreshCw className="mr-1 h-3 w-3" /> {t('repository.sync')}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 text-xs text-destructive"
                    onClick={() => disconnectRepo(r)}
                    disabled={busy}
                  >
                    <Unplug className="mr-1 h-3 w-3" /> {t('repository.disconnect')}
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Detail panel: PRs and commits for selected repo */}
      {selected && (
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-base flex items-center gap-2">
                <GitPullRequest className="h-4 w-4" /> {selected.fullName}
              </CardTitle>
              <div className="flex items-center gap-2">
                <div className="flex gap-1 border rounded-md p-0.5">
                  <button
                    onClick={() => setActiveTab('pulls')}
                    className={`flex items-center gap-1 px-3 py-1 rounded text-xs font-medium transition-colors ${
                      activeTab === 'pulls' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'
                    }`}
                  >
                    <GitPullRequest className="h-3 w-3" />
                    {t('repository.tabPRs', 'Pull Requests')} ({pulls.length})
                  </button>
                  <button
                    onClick={() => setActiveTab('commits')}
                    className={`flex items-center gap-1 px-3 py-1 rounded text-xs font-medium transition-colors ${
                      activeTab === 'commits' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'
                    }`}
                  >
                    <GitCommit className="h-3 w-3" />
                    {t('repository.tabCommits', 'Commits')} ({commits.length})
                  </button>
                </div>
                {activeTab === 'pulls' && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => githubApi
                      .ingestPullRequests(workspaceId, selected.id, 'all')
                      .then(() => loadDetails(selected))
                      .catch((err) => setError(describeError(err)))}
                    disabled={busy}
                  >
                    <RefreshCw className="mr-1 h-3 w-3" /> {t('repository.ingestAll')}
                  </Button>
                )}
                {activeTab === 'commits' && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={async () => {
                      try {
                        setBusy(true);
                        const result = await githubApi.ingestCommits(workspaceId, selected.id, 50);
                        setInfo(t('repository.commitsIngested', {
                          count: result.ingested,
                          name: selected.fullName,
                        }));
                        await loadDetails(selected);
                      } catch (err) {
                        setError(describeError(err));
                      } finally {
                        setBusy(false);
                      }
                    }}
                    disabled={busy}
                  >
                    <RefreshCw className="mr-1 h-3 w-3" /> {t('repository.fetchFromGithub', 'Fetch from GitHub')}
                  </Button>
                )}
              </div>
            </div>
          </CardHeader>
          <CardContent className="p-0">

            {/* Pull Requests Tab */}
            {activeTab === 'pulls' && (
              <>
                {prsLoading ? (
                  <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" /> {t('repository.loadingPRs')}
                  </div>
                ) : pulls.length === 0 ? (
                  <p className="text-sm text-muted-foreground px-4 py-6">
                    {t('repository.noPRs')}
                  </p>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>#</TableHead>
                        <TableHead>{t('repository.colPRTitle')}</TableHead>
                        <TableHead>{t('repository.colPRState')}</TableHead>
                        <TableHead>{t('repository.colPRAuthor')}</TableHead>
                        <TableHead>{t('repository.colPRBranch')}</TableHead>
                        <TableHead className="text-right">{t('repository.colPRUpdated')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {pulls.map((p) => (
                        <TableRow key={p.id}>
                          <TableCell className="font-mono text-xs">#{p.number}</TableCell>
                          <TableCell>
                            {p.htmlUrl ? (
                              <a
                                href={p.htmlUrl}
                                target="_blank"
                                rel="noreferrer"
                                className="hover:text-primary flex items-center gap-1"
                              >
                                {p.title}
                                <ExternalLink className="h-3 w-3" />
                              </a>
                            ) : (
                              p.title
                            )}
                            {p.isDraft && (
                              <Badge variant="muted" className="ml-2 text-[10px]">draft</Badge>
                            )}
                          </TableCell>
                          <TableCell>
                            <PRStateBadge state={p.state} />
                          </TableCell>
                          <TableCell className="text-xs">{p.authorLogin || '—'}</TableCell>
                          <TableCell className="font-mono text-xs">
                            {p.headBranch} → {p.baseBranch}
                          </TableCell>
                          <TableCell className="text-right text-xs text-muted-foreground">
                            {new Date(p.updatedAt).toLocaleDateString()}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </>
            )}

            {/* Commits Tab */}
            {activeTab === 'commits' && (
              <>
                {commitsLoading ? (
                  <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" /> {t('repository.loadingCommits', 'Loading commits...')}
                  </div>
                ) : commits.length === 0 ? (
                  <div className="text-center py-8 px-4">
                    <GitCommit className="h-8 w-8 text-muted-foreground mx-auto mb-3" />
                    <p className="text-sm text-muted-foreground mb-2">
                      {t('repository.noCommits', 'No commits found')}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {t('repository.noCommitsHint', 'Click "Fetch from GitHub" to load commits from this repository.')}
                    </p>
                  </div>
                ) : (
                  <div className="divide-y">
                    {commits.map((c) => (
                      <div key={c.id} className="px-4 py-3 hover:bg-muted/20 transition-colors">
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2 flex-wrap">
                              {/* SHA */}
                              {c.url ? (
                                <a
                                  href={c.url}
                                  target="_blank"
                                  rel="noreferrer"
                                  className="font-mono text-xs text-primary hover:underline"
                                >
                                  {c.shortSha || c.sha?.substring(0, 7)}
                                </a>
                              ) : (
                                <span className="font-mono text-xs text-muted-foreground">
                                  {c.shortSha || c.sha?.substring(0, 7)}
                                </span>
                              )}
                              {/* Branch */}
                              {c.branch && (
                                <Badge variant="muted" className="text-[10px] flex items-center gap-0.5">
                                  <GitBranch className="h-2.5 w-2.5" />
                                  {c.branch}
                                </Badge>
                              )}
                              {/* Author */}
                              {c.authorName && (
                                <span className="text-xs text-muted-foreground">
                                  {c.authorName}
                                </span>
                              )}
                            </div>
                            {/* Message */}
                            <p className="text-sm mt-1 line-clamp-2">
                              {c.message}
                            </p>
                            {/* Meta */}
                            <div className="flex items-center gap-3 mt-1 text-xs text-muted-foreground">
                              {c.authorEmail && (
                                <span>{c.authorEmail}</span>
                              )}
                              <span>
                                {c.authoredAt ? new Date(c.authoredAt).toLocaleString() : ''}
                              </span>
                              {c.url && (
                                <a
                                  href={c.url}
                                  target="_blank"
                                  rel="noreferrer"
                                  className="flex items-center gap-1 hover:text-primary"
                                >
                                  <ExternalLink className="h-3 w-3" />
                                  GitHub
                                </a>
                              )}
                            </div>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </CardContent>
        </Card>
      )}

      {/* Connect dialog */}
      <Dialog open={showConnect} onOpenChange={setShowConnect}>
        <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t('repository.addRepo')}</DialogTitle>
            <DialogDescription>{t('repository.addRepoDesc')}</DialogDescription>
          </DialogHeader>

          <div className="flex gap-2 border-b">
            <Button
              variant={tab === 'catalog' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setTab('catalog')}
              className="rounded-b-none"
            >
              <Github className="mr-1 h-4 w-4" /> {t('repository.tabCatalog')}
            </Button>
            <Button
              variant={tab === 'url' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setTab('url')}
              className="rounded-b-none"
            >
              <GitBranch className="mr-1 h-4 w-4" /> {t('repository.tabUrl')}
            </Button>
          </div>

          {tab === 'catalog' ? (
            <div className="space-y-3 pt-2">
              <Input
                value={catalogSearch}
                onChange={(e) => setCatalogSearch(e.target.value)}
                placeholder={t('repository.searchCatalogPlaceholder')}
              />
              {catalogLoading ? (
                <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                  <Loader2 className="h-4 w-4 animate-spin" /> {t('repository.loadingCatalog')}
                </div>
              ) : catalogErr ? (
                // Check if it's an OAuth error - show friendly prompt instead of raw error
                catalogErr.includes('No GitHub connection') || catalogErr.includes('github') ? (
                  <div className="flex flex-col items-center justify-center py-8 text-center">
                    <Github className="h-10 w-10 text-muted-foreground mb-3" />
                    <p className="text-sm font-medium mb-1">{t('repository.githubRequired', 'GitHub connection required')}</p>
                    <p className="text-xs text-muted-foreground mb-4">
                      {t('repository.githubRequiredDesc', 'Connect your GitHub account to browse repositories.')}
                    </p>
                    <Button size="sm" onClick={startGithubOAuth}>
                      <Github className="mr-1 h-4 w-4" /> {t('repository.connectGithub')}
                    </Button>
                  </div>
                ) : (
                  <ErrorState message={catalogErr} />
                )
              ) : filteredCatalog.length === 0 ? (
                <p className="text-sm text-muted-foreground p-3">
                  {t('repository.emptyCatalog')}
                </p>
              ) : (
                <div className="space-y-1 max-h-96 overflow-y-auto">
                  {filteredCatalog.map((c) => (
                    <div
                      key={c.githubId}
                      className="flex items-center justify-between rounded-md border px-3 py-2 hover:bg-muted/30"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-medium truncate">{c.fullName}</div>
                        <div className="text-xs text-muted-foreground flex items-center gap-2">
                          <GitBranch className="h-3 w-3" />
                          {c.defaultBranch}
                          {c.isPrivate && <Badge variant="muted" className="text-[10px]">private</Badge>}
                          {c.isArchived && <Badge variant="muted" className="text-[10px]">archived</Badge>}
                        </div>
                        {c.description && (
                          <div className="text-xs text-muted-foreground line-clamp-1">
                            {c.description}
                          </div>
                        )}
                      </div>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => connectFromCatalog(c.githubId)}
                        disabled={busy}
                      >
                        {t('repository.connect')}
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <form onSubmit={(e: FormEvent) => { e.preventDefault(); void connectFromUrl(); }} className="space-y-3 pt-2">
              <div className="space-y-1.5">
                <Label htmlFor="repo-url">{t('repository.urlLabel')}</Label>
                <Input
                  id="repo-url"
                  type="url"
                  value={urlInput}
                  onChange={(e) => setUrlInput(e.target.value)}
                  placeholder="https://github.com/owner/repo"
                  required
                />
                <p className="text-xs text-muted-foreground">{t('repository.urlHint')}</p>
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={() => setShowConnect(false)}>
                  {t('common.cancel')}
                </Button>
                <Button type="submit" disabled={busy || !urlInput.trim()}>
                  {busy ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <GitBranch className="mr-1 h-4 w-4" />}
                  {t('repository.connect')}
                </Button>
              </DialogFooter>
            </form>
          )}

          <DialogFooter>
            <Button variant="ghost" size="sm" asChild>
              <Link to={`/workspaces/${workspaceId}/integrations`}>
                <Sparkles className="mr-1 h-3 w-3" /> {t('repository.manageConnections')}
              </Link>
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function StatusBadge({ status }: { status: Repository['status'] }) {
  const variant =
    status === 'CONNECTED'
      ? 'success'
      : status === 'ERROR'
      ? 'destructive'
      : status === 'ARCHIVED'
      ? 'muted'
      : 'muted';
  return <Badge variant={variant} className="text-[10px]">{status}</Badge>;
}

function PRStateBadge({ state }: { state: PullRequest['state'] }) {
  const variant =
    state === 'OPEN' ? 'info' : state === 'MERGED' ? 'success' : 'muted';
  return <Badge variant={variant} className="text-[10px]">{state}</Badge>;
}
